/*
 * Copyright (c) 2023 Macula
 *   macula.dev, China
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.macula.cloud.iam.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 授权码 + PKCE 的 BFF 端点（P3-2 路线 A）：token 只存服务端 Redis，浏览器仅持有
 * HttpOnly SESSION Cookie。全链路：authorize 发起 → SAS 登录/授权 → callback 换 token
 * 存 session → 网关凭 cookie 注入 Authorization（懒刷新走 /auth/refresh）→ logout 吊销。
 * <p>
 * Redis 约定（与网关 SessionAuthGlobalFilter 共享）：key {@code bff:session:{sessionId}} →
 * JSON {@code {"access_token","refresh_token","expire_at","token_type"}}；key
 * {@code bff:authreq:{state}} → code_verifier（TTL 5 分钟，一次性）。
 *
 * @author macula
 * @since 6.0.0
 */
@Slf4j
@RestController
public class AuthBffController {

    public static final String SESSION_COOKIE = "MACULA_SESSION";
    public static final String SESSION_KEY_PREFIX = "bff:session:";
    static final String AUTHREQ_KEY_PREFIX = "bff:authreq:";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration AUTHREQ_TTL = Duration.ofMinutes(5);
    /** refresh_token 生命周期 30d（sys_oauth2_client.refresh_token_time_to_live=2592000） */
    private static final Duration SESSION_TTL = Duration.ofDays(30);

    private final RestTemplate restTemplate = new RestTemplate();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${macula.oauth2.client-id}")
    private String clientId;

    @Value("${macula.oauth2.client-secret}")
    private String clientSecret;

    /** 授权码回调地址（client 表 redirect_uris 必须含此值；生产改为网关域名下的 /auth/callback） */
    @Value("${macula.oauth2.bff.callback-url:http://localhost:9010/auth/callback}")
    private String callbackUrl;

    /** 登录成功后的前端首页 */
    @Value("${macula.oauth2.bff.web-home-url:http://localhost:5900/}")
    private String webHomeUrl;

    /** 登出后的前端登录页 */
    @Value("${macula.oauth2.bff.web-login-url:http://localhost:5900/#/login}")
    private String webLoginUrl;

    /** BFF 请求的 scope（固定值，不透传前端参数） */
    @Value("${macula.oauth2.bff.scope:userinfo}")
    private String scope;

    /** web server 就绪后回填实际端口（兼容测试 RANDOM_PORT 与生产固定端口） */
    private volatile int serverPort = 9010;

    @EventListener
    public void onWebServerReady(WebServerInitializedEvent event) {
        this.serverPort = event.getWebServer().getPort();
    }

    public AuthBffController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 发起授权码 + PKCE 流程：生成 state/code_verifier 存 Redis 后 302 到 SAS 授权端点。
     * 未登录时 SAS 会先跳登录页；require_authorization_consent=0（第一方）不再弹确认页。
     */
    @GetMapping("/auth/authorize")
    public ResponseEntity<Void> authorize() {
        String state = randomToken();
        String codeVerifier = randomToken();

        redisTemplate.opsForValue().set(AUTHREQ_KEY_PREFIX + state, codeVerifier, AUTHREQ_TTL);

        String authorizeUrl = UriComponentsBuilder
            .fromHttpUrl("http://127.0.0.1:" + serverPort + "/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", clientId)
            .queryParam("redirect_uri", callbackUrl)
            .queryParam("scope", scope)
            .queryParam("state", state)
            .queryParam("code_challenge", s256Challenge(codeVerifier))
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();

        return ResponseEntity.status(302).location(java.net.URI.create(authorizeUrl)).build();
    }

    /**
     * 授权码回调：state 一次性校验 → code + verifier 换 token → 存 session → 下发
     * HttpOnly Cookie 并 302 前端首页。
     */
    @GetMapping("/auth/callback")
    public ResponseEntity<Void> callback(@RequestParam("code") String code, @RequestParam("state") String state) {
        // DEL 返回被删值：state 一次性（重放即失效）
        String codeVerifier = redisTemplate.opsForValue().getAndDelete(AUTHREQ_KEY_PREFIX + state);
        if (codeVerifier == null) {
            log.warn("BFF callback with unknown/expired state (possible replay), redirect to login");
            return redirect(webLoginUrl + "?error=invalid_state");
        }

        JsonNode token = requestToken(authorizationCodeForm(code, codeVerifier));
        String sessionId = randomToken();
        storeSession(sessionId, token);

        return ResponseEntity.status(302)
            .location(java.net.URI.create(webHomeUrl))
            .header(HttpHeaders.SET_COOKIE, sessionCookie(sessionId, -1))
            .build();
    }

    /**
     * 网关懒刷新端点：凭 session 中的 refresh_token 换新 access_token 并更新 session。
     * client_secret 只在本服务持有（网关不触碰）。返回最新 session JSON。
     */
    @PostMapping("/auth/refresh")
    public ResponseEntity<String> refresh(@CookieValue(SESSION_COOKIE) String sessionId) {
        String sessionJson = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + sessionId);
        if (sessionJson == null) {
            return ResponseEntity.status(401).build();
        }
        try {
            JsonNode session = objectMapper.readTree(sessionJson);
            String refreshToken = session.path("refresh_token").asText(null);
            if (refreshToken == null || refreshToken.isEmpty()) {
                return ResponseEntity.status(401).body("{\"error\":\"no_refresh_token\"}");
            }
            JsonNode token = requestToken(refreshTokenForm(refreshToken));
            // SAS reuseRefreshTokens=1 不轮换 refresh_token，响应可能无新值——沿用旧值
            String newSession = mergeSession(token, session);
            redisTemplate.opsForValue().set(SESSION_KEY_PREFIX + sessionId, newSession, SESSION_TTL);
            return ResponseEntity.ok(newSession);
        } catch (HttpStatusCodeException e) {
            // refresh_token 已吊销/过期：session 终结
            redisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
            return ResponseEntity.status(e.getStatusCode().value()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("BFF refresh failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 登出：吊销 access/refresh token（SAS /oauth2/revoke）+ 删除 session + 过期 Cookie。
     */
    @GetMapping("/auth/logout")
    public ResponseEntity<Void> logout(@CookieValue(value = SESSION_COOKIE, required = false) String sessionId) {
        if (sessionId != null) {
            String sessionJson = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + sessionId);
            if (sessionJson != null) {
                try {
                    JsonNode session = objectMapper.readTree(sessionJson);
                    revokeToken(session.path("access_token").asText(null));
                    revokeToken(session.path("refresh_token").asText(null));
                } catch (Exception ignored) {
                    // 吊销失败不阻断登出
                }
                redisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
            }
        }
        return ResponseEntity.status(302)
            .location(java.net.URI.create(webLoginUrl))
            .header(HttpHeaders.SET_COOKIE, sessionCookie("", 0))
            .build();
    }

    // ---------------------------------------------------------------------

    private JsonNode requestToken(MultiValueMap<String, String> form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(clientId, clientSecret, StandardCharsets.UTF_8);

        String tokenUrl = "http://127.0.0.1:" + serverPort + "/oauth2/token";
        ResponseEntity<String> response =
            restTemplate.postForEntity(tokenUrl, new HttpEntity<>(form, headers), String.class);
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Token endpoint returned non-JSON body", e);
        }
    }

    private MultiValueMap<String, String> authorizationCodeForm(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", callbackUrl);
        form.add("code_verifier", codeVerifier);
        return form;
    }

    private MultiValueMap<String, String> refreshTokenForm(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return form;
    }

    private void storeSession(String sessionId, JsonNode token) {
        Map<String, Object> session = new HashMap<>();
        session.put("access_token", token.path("access_token").asText());
        session.put("refresh_token", token.path("refresh_token").asText(""));
        session.put("token_type", token.path("token_type").asText("Bearer"));
        session.put("expire_at", System.currentTimeMillis() + token.path("expires_in").asLong(0) * 1000);
        try {
            redisTemplate.opsForValue().set(SESSION_KEY_PREFIX + sessionId,
                objectMapper.writeValueAsString(session), SESSION_TTL);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist BFF session", e);
        }
    }

    private String mergeSession(JsonNode token, JsonNode oldSession) {
        Map<String, Object> session = new HashMap<>();
        session.put("access_token", token.path("access_token").asText(oldSession.path("access_token").asText()));
        String newRefresh = token.path("refresh_token").asText("");
        session.put("refresh_token", newRefresh.isEmpty() ? oldSession.path("refresh_token").asText("") : newRefresh);
        session.put("token_type", token.path("token_type").asText(oldSession.path("token_type").asText("Bearer")));
        session.put("expire_at", System.currentTimeMillis() + token.path("expires_in").asLong(0) * 1000);
        try {
            return objectMapper.writeValueAsString(session);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void revokeToken(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(clientId, clientSecret, StandardCharsets.UTF_8);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        try {
            restTemplate.postForEntity("http://127.0.0.1:" + serverPort + "/oauth2/revoke",
                new HttpEntity<>(form, headers), String.class);
        } catch (HttpStatusCodeException e) {
            log.warn("Token revoke failed: {}", e.getResponseBodyAsString());
        }
    }

    private String sessionCookie(String value, long maxAgeSeconds) {
        // SameSite=Lax：top-level GET 跳转（授权回跳）携带；HttpOnly 防脚本读取
        return SESSION_COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Lax"
            + (maxAgeSeconds >= 0 ? "; Max-Age=" + maxAgeSeconds : "");
    }

    private static ResponseEntity<Void> redirect(String url) {
        return ResponseEntity.status(302).location(java.net.URI.create(url)).build();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); // 43 chars
    }

    /** RFC 7636 S256：BASE64URL(SHA-256(ascii(verifier))) */
    private static String s256Challenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
