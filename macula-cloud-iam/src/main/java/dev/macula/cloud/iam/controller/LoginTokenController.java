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

import cn.hutool.core.util.StrUtil;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/**
 * 登录令牌代理（BFF）：前端不再持有 client_secret，凭证由服务端注入。
 * <p>
 * 生产环境通过环境变量覆盖：MACULA_OAUTH2_CLIENT_ID / MACULA_OAUTH2_CLIENT_SECRET。
 *
 * @author macula
 * @since 6.0.0
 */
@RestController
public class LoginTokenController {

    private final RestTemplate restTemplate = new RestTemplate();

    /** web server 就绪后回填实际端口（兼容测试 RANDOM_PORT 与生产固定端口） */
    private volatile int serverPort = 9010;

    @Value("${macula.oauth2.client-id}")
    private String clientId;

    @Value("${macula.oauth2.client-secret}")
    private String clientSecret;

    @EventListener
    public void onWebServerReady(WebServerInitializedEvent event) {
        this.serverPort = event.getWebServer().getPort();
    }

    /**
     * 代理 password grant 换取 token；错误响应（含限流）原样透传给前端。
     *
     * @param body username / password / scope(可选)
     */
    @PostMapping(value = "/login/token", consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> issueToken(@RequestBody Map<String, String> body) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("username", body.getOrDefault("username", ""));
        form.add("password", body.getOrDefault("password", ""));
        String scope = body.get("scope");
        if (StrUtil.isNotBlank(scope)) {
            form.add("scope", scope);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        String basic = Base64.getEncoder()
            .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        headers.add(HttpHeaders.AUTHORIZATION, "Basic " + basic);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                "http://127.0.0.1:" + serverPort + "/oauth2/token", HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
            return ResponseEntity.status(resp.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(resp.getBody());
        } catch (HttpStatusCodeException e) {
            // 4xx/5xx（BadCredentials、锁定限流等）透传原文
            return ResponseEntity.status(e.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(e.getResponseBodyAsString());
        }
    }
}
