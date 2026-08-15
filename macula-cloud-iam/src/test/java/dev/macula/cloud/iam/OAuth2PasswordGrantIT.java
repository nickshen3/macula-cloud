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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 认证链路集成测试（P1-2）
 *
 * 覆盖：password grant 成功/密码错误/限流锁定/成功清计数/refresh_token。
 * 环境：Testcontainers 临时 MySQL（复用 docs dump 初始化）+ Redis，禁用 Nacos。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false",
    // P2-4: 配置已迁 Nacos；测试环境无 Nacos，置空 config.import 使业务配置从 test classpath 的 application.yml 加载
    "spring.config.import="})
@Testcontainers
@DisplayName("OAuth2 密码模式认证链路")
class OAuth2PasswordGrantIT {

    private static final String CLIENT_ID = "e4da4a32-592b-46f0-ae1d-784310e88423";
    private static final String CLIENT_SECRET = "secret";
    private static final String TOKEN_URL = "/oauth2/token";

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

    @BeforeEach
    void resetLoginCounters() throws Exception {
        // 每个用例独立：清空登录失败计数
        REDIS.execInContainer("redis-cli", "FLUSHDB");
    }

    private Map<String, Object> requestToken(String grantType, String... form) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(CLIENT_ID, CLIENT_SECRET);
        StringBuilder body = new StringBuilder("grant_type=").append(grantType);
        for (int i = 0; i < form.length; i += 2) {
            body.append('&').append(form[i]).append('=').append(form[i + 1]);
        }
        return rest.postForObject(TOKEN_URL, new HttpEntity<>(body.toString(), headers), Map.class);
    }

    @Test
    @DisplayName("BFF 代理登录 /login/token → 无需 client 凭证即可签发，错误透传")
    void bffLoginTokenProxy() {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        // 成功：仅传 username/password/scope，不携带任何 client 凭证
        Map<String, Object> ok = rest.postForObject("/login/token",
            new HttpEntity<>("{\"username\":\"admin\",\"password\":\"admin\",\"scope\":\"userinfo\"}", json), Map.class);
        assertNotNull(ok.get("access_token"), () -> "BFF 代理应签发 token: " + ok);
        // 失败：错误密码的错误体应原样透传（不被包装成 server_error）
        Map<String, Object> bad = rest.postForObject("/login/token",
            new HttpEntity<>("{\"username\":\"admin\",\"password\":\"WRONG\",\"scope\":\"userinfo\"}", json), Map.class);
        assertEquals("bad_credentials", bad.get("error"), () -> String.valueOf(bad));
    }

    @Test
    @DisplayName("正确账密 → 签发 access_token 且用户信息正确")
    void passwordGrantSuccess() {
        Map<String, Object> resp = requestToken("password", "username", "admin", "password", "admin",
            "scope", "userinfo");
        assertNotNull(resp.get("access_token"), () -> "应返回 access_token: " + resp);
        assertNotNull(resp.get("refresh_token"), "应返回 refresh_token");
        assertEquals("admin", resp.get("sub"), "token claims 应含用户名 sub");
    }

    @Test
    @DisplayName("错误密码 → bad_credentials")
    void passwordGrantWrongPassword() {
        Map<String, Object> resp = requestToken("password", "username", "admin", "password", "WRONG",
            "scope", "userinfo");
        assertEquals("bad_credentials", resp.get("error"), () -> String.valueOf(resp));
    }

    @Test
    @DisplayName("连续 5 次失败 → 第 6 次被限流锁定 access_denied")
    void loginLockedAfterFiveFailures() {
        for (int i = 0; i < 5; i++) {
            Map<String, Object> resp = requestToken("password", "username", "admin", "password", "WRONG",
                "scope", "userinfo");
            assertEquals("bad_credentials", resp.get("error"), "前 5 次应为密码错误");
        }
        Map<String, Object> locked = requestToken("password", "username", "admin", "password", "admin",
            "scope", "userinfo");
        assertEquals("access_denied", locked.get("error"), () -> "第 6 次应被锁定: " + locked);
        assertNotNull(locked.get("error_description"), "锁定提示语应存在");
    }

    @Test
    @DisplayName("登录成功清账号计数；IP 维度阈值宽容 NAT 误输")
    void successClearsFailureCounter() {
        // 第一轮：错 4 次（acct=4, ip=4）
        for (int i = 0; i < 4; i++) {
            requestToken("password", "username", "admin", "password", "WRONG", "scope", "userinfo");
        }
        Map<String, Object> ok = requestToken("password", "username", "admin", "password", "admin",
            "scope", "userinfo");
        assertNotNull(ok.get("access_token"), "第 5 次前成功登录应放行");
        // 第二轮：再错 4 次（acct 重新计 4，ip 累计 8 < 10）
        for (int i = 0; i < 4; i++) {
            requestToken("password", "username", "admin", "password", "WRONG", "scope", "userinfo");
        }
        Map<String, Object> ok2 = requestToken("password", "username", "admin", "password", "admin",
            "scope", "userinfo");
        assertNotNull(ok2.get("access_token"), "账号计数已清零且 IP 未达 10 次阈值，不应锁定");
    }

    @Test
    @DisplayName("IP 维度累计 10 次后即使账号正确也锁定")
    void ipDimensionLocksAfterTenFailures() {
        // 两个"周期"：每轮错 4 次+成功 1 次，账号维度永远不锁，IP 维度累计到 8
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 4; i++) {
                requestToken("password", "username", "admin", "password", "WRONG", "scope", "userinfo");
            }
            requestToken("password", "username", "admin", "password", "admin", "scope", "userinfo");
        }
        // 第 9 次失败（ip=9）
        requestToken("password", "username", "admin", "password", "WRONG", "scope", "userinfo");
        // 第 10 次失败（check 时 ip=9 放行，失败后 ip=10）
        requestToken("password", "username", "admin", "password", "WRONG", "scope", "userinfo");
        // 第 11 次：check 时 ip=10 → 锁定
        Map<String, Object> locked = requestToken("password", "username", "admin", "password", "WRONG",
            "scope", "userinfo");
        assertEquals("access_denied", locked.get("error"), () -> "IP 累计 10 次应锁定: " + locked);
    }

    @Test
    @DisplayName("refresh_token 可换发新 access_token")
    void refreshTokenGrant() {
        Map<String, Object> first = requestToken("password", "username", "admin", "password", "admin",
            "scope", "message.read message.write userinfo");
        String refreshToken = (String)first.get("refresh_token");
        assertNotNull(refreshToken, "password 模式应签发 refresh_token");

        Map<String, Object> refreshed =
            requestToken("refresh_token", "refresh_token", refreshToken, "scope", "userinfo");
        assertNotNull(refreshed.get("access_token"), () -> "refresh 应换发新 token: " + refreshed);
        assertNotEquals(first.get("access_token"), refreshed.get("access_token"), "新旧 access_token 应不同");
    }
}
