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

package dev.macula.cloud.iam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * 授权码 + PKCE 的 BFF 全链路集成测试（P3-2）
 *
 * 覆盖：authorize 发起（302 + S256 challenge）/ 表单登录后授权码签发 / callback
 * 换 token 与 HttpOnly Cookie / state 重放拒绝 / token introspect active。
 * HTTP 层使用 JDK HttpClient（默认不跟随重定向，逐跳读取 Location 与 Set-Cookie）。
 * 环境：Testcontainers 临时 MySQL（docs dump 初始化）+ Redis，禁用 Nacos。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
    "spring.config.import=",
    // callback-url 保持默认（=client 表注册值字符串 localhost:9010/auth/callback）；
    // 测试内把回调 URL 改写为随机端口后请求
    "macula.oauth2.bff.web-home-url=http://127.0.0.1:5900/"})
@Testcontainers
@DisplayName("OAuth2 授权码+PKCE BFF 链路")
class OAuth2AuthCodeIT {

    private static final String CLIENT_ID = "e4da4a32-592b-46f0-ae1d-784310e88423";
    private static final String CLIENT_SECRET = "secret";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.35")
        .withCopyFileToContainer(
            MountableFile.forHostPath(Paths.get("..", "macula-cloud-system", "docs", "macula-system-dump.sql")),
            "/docker-entrypoint-initdb.d/01-macula-system.sql")
        .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
            () -> MYSQL.getJdbcUrl().replaceAll("/test$", "/macula-system") + "?useUnicode=true&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", () -> MYSQL.getPassword());
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    TestRestTemplate rest;

    private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
    private final Map<String, String> cookieJar = new java.util.HashMap<>();

    @BeforeEach
    void flushCounters() throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHDB");
    }

    @Test
    @DisplayName("authorize 发起：302 到 SAS 且携带 S256 challenge 与 state")
    void authorizeRedirectsToSasWithPkce() throws Exception {
        String[] resp = httpGet(root() + "/auth/authorize");
        assertEquals("302", resp[0], "应 302 到 SAS，body=" + resp[1]);
        String location = resp[1];
        String query = URI.create(location).getRawQuery();
        assertTrue(query.contains("response_type=code"), "response_type");
        assertTrue(query.contains("client_id=" + CLIENT_ID), "client_id");
        assertTrue(query.contains("code_challenge_method=S256"), "S256");
        assertTrue(query.contains("code_challenge="), "challenge 存在");
        assertTrue(query.contains("state="), "state 存在");
        assertTrue(URI.create(location).getPath().endsWith("/oauth2/authorize"));
    }

    @Test
    @DisplayName("callback：无效/重放 state 被拒绝（不发 Cookie）")
    void callbackRejectsInvalidState() throws Exception {
        cookieJar.clear();
        String[] resp = httpGet(root() + "/auth/callback?code=whatever&state=forged");
        assertEquals("302", resp[0], "应 302 到登录页，body=" + resp[1]);
        assertTrue(resp[1].contains("error=invalid_state"), "error=invalid_state: " + resp[1]);
        assertTrue(!cookieJar.containsKey("MACULA_SESSION"), "不得下发会话 Cookie");
    }

    @Test
    @DisplayName("全链路：表单登录→授权码→callback 换 token→HttpOnly Cookie→token introspect active")
    void fullAuthCodeFlow() throws Exception {
        cookieJar.clear();
        // ① 发起
        String[] authorize = httpGet(root() + "/auth/authorize");
        assertEquals("302", authorize[0]);
        String sasUrl = authorize[1];
        String state = paramOf(sasUrl, "state");
        String challenge = paramOf(sasUrl, "code_challenge");
        assertTrue(challenge != null && challenge.length() >= 43, "challenge 长度");

        // ② SAS 授权端点（未登录 → 302 /login）
        String[] sasResp = httpGet(sasUrl);
        assertEquals("302", sasResp[0], "未登录应跳登录页");

        // ③ 表单登录（csrf 已禁用；JSESSIONID 由 cookieJar 携带）
        String[] login = httpPostForm(root() + "/login", "username=admin&password=admin");
        assertTrue(login[1].contains("\"success\":true"), "登录应成功: " + brief(login[1]));

        // ④ 登录后重放 SAS 授权请求 → 拿到 code（302 到 callback 地址）
        String[] authz = httpGet(sasUrl);
        assertEquals("302", authz[0], "授权后应 302 回调，body=" + brief(authz[1]));
        String redirect = authz[1];
        assertTrue(redirect.contains("/auth/callback?code="), "应携带授权码回调，实际: " + redirect);
        assertEquals(state, paramOf(redirect, "state"), "state 原样回传");

        // ⑤ BFF callback：换 token + 下发 HttpOnly Cookie
        //    （redirect_uri 字符串=注册值 localhost:9010；把实际请求改写到本进程随机端口）
        String realCallback = redirect.replace("localhost:9010", root().replace("http://", ""));
        String[] callback = httpGet(realCallback);
        assertEquals("302", callback[0]);
        String sessionId = cookieJar.get("MACULA_SESSION");
        assertNotNull(sessionId, "应下发 MACULA_SESSION Cookie");

        // ⑥ session 中的 token 经 introspect 验证 active（等价网关/上游校验语义）
        String sessionJson = redisGet("bff:session:" + sessionId);
        assertNotNull(sessionJson, "Redis 应有 session");
        String accessToken = jsonField(sessionJson, "access_token");
        assertNotNull(accessToken);

        HttpHeaders introspectHeaders = new HttpHeaders();
        introspectHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        introspectHeaders.setBasicAuth(CLIENT_ID, CLIENT_SECRET);
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("token", accessToken);
        ResponseEntity<String> introspect = rest.postForEntity("/oauth2/introspect",
            new HttpEntity<>(body, introspectHeaders), String.class);
        assertTrue(introspect.getBody() != null && introspect.getBody().contains("\"active\":true"), "token active");
    }

    // ---------------- JDK HttpClient 工具：默认不跟随重定向，手动携带 Cookie ----------------

    private String root() {
        return rest.getRootUri();
    }

    private static String brief(String s) {
        return s == null ? "" : s.substring(0, Math.min(150, s.length()));
    }

    /** 返回 [status, locationOrBody]；自动收集 Set-Cookie 并在后续请求携带 */
    private String[] httpGet(String url) throws Exception {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(URI.create(url)).GET();
        cookieHeader(b);
        java.net.http.HttpResponse<String> resp =
            http.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        absorbCookies(resp);
        return new String[] {String.valueOf(resp.statusCode()),
            resp.headers().firstValue("Location").orElse(resp.body())};
    }

    private String[] httpPostForm(String url, String form) throws Exception {
        java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form));
        cookieHeader(b);
        java.net.http.HttpResponse<String> resp =
            http.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        absorbCookies(resp);
        return new String[] {String.valueOf(resp.statusCode()),
            resp.headers().firstValue("Location").orElse(resp.body())};
    }

    private void cookieHeader(java.net.http.HttpRequest.Builder b) {
        if (!cookieJar.isEmpty()) {
            b.header("Cookie", cookieJar.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, e) -> a + "; " + e).orElse(""));
        }
    }

    private void absorbCookies(java.net.http.HttpResponse<String> resp) {
        resp.headers().allValues("Set-Cookie").forEach(sc -> {
            String[] pair = sc.split(";", 2)[0].split("=", 2);
            if (pair.length == 2 && !pair[1].isEmpty()) {
                cookieJar.put(pair[0].trim(), pair[1].trim());
            } else if (pair.length == 1 || (pair.length == 2 && pair[1].isEmpty())) {
                cookieJar.remove(pair[0].trim()); // Max-Age=0 的清除型 Cookie
            }
        });
    }

    private static String paramOf(String url, String name) {
        Matcher m = Pattern.compile("[?&]" + Pattern.quote(name) + "=([^&]+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private String redisGet(String key) {
        try {
            org.testcontainers.containers.Container.ExecResult r = REDIS.execInContainer("redis-cli", "GET", key);
            return r.getStdout().trim();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jsonField(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\":\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
