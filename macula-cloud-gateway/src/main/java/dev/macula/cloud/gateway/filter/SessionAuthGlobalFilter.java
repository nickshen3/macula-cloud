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

package dev.macula.cloud.gateway.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * P3-2 路线 A：SESSION Cookie → BFF session（Redis）→ Authorization 头注入。
 * <p>
 * 以 WebFilter（order=-200）实现而非 GlobalFilter：网关自带的 SecurityWebFilterChain
 * （WebFilterChainProxy，order=-100）先于 Gateway GlobalFilter 执行 opaque introspection，
 * 注入必须发生在安全链之前。token 全程不出服务端：浏览器仅持 HttpOnly Cookie；
 * access_token 临期（&lt;60s）时经 IAM {@code /auth/refresh} 懒刷新（client_secret 只在
 * IAM 持有，网关不触碰）。session 缺失或失效时不注入（由安全链 401，前端引导重新授权）。
 *
 * @author macula
 * @since 6.0.0
 */
@Slf4j
@Component
public class SessionAuthGlobalFilter implements WebFilter, Ordered {

    static final String SESSION_COOKIE = "MACULA_SESSION";
    static final String SESSION_KEY_PREFIX = "bff:session:";
    /** 临期阈值：剩余有效期不足 60s 时懒刷新 */
    private static final long REFRESH_THRESHOLD_MS = 60_000L;

    private final RedissonClient redisson;
    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${macula.oauth2.bff.base-url:http://127.0.0.1:9010}")
    private String iamBaseUrl;

    public SessionAuthGlobalFilter(RedissonClient redisson, WebClient.Builder webClientBuilder) {
        this.redisson = redisson;
        this.webClient = webClientBuilder.build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(SESSION_COOKIE);
        if (cookie == null) {
            return chain.filter(exchange);
        }
        String sessionId = cookie.getValue();
        // 注意：chain.filter(...) 返回 Mono<Void>（永远 empty），不能对它套 switchIfEmpty，
        // 否则会用原始 exchange 二次转发丢失注入——用 defaultIfEmpty 哨兵分流
        // StringCodec 直读 IAM StringRedisTemplate 写入的原始 JSON（网关 Redisson 默认 Kryo5 解不了）
        // publishOn：redisson-reactive 完成信号在 Redisson netty 线程，若不切线程，后续
        // Security 链里的阻塞 Redis introspection 会触发 Redisson 的 sync-on-netty 防死锁检测
        return redisson.reactive().<String>getBucket(SESSION_KEY_PREFIX + sessionId, StringCodec.INSTANCE).get()
            .defaultIfEmpty("")
            .publishOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .flatMap(sessionJson -> sessionJson.isEmpty()
                ? chain.filter(exchange) // 无 session：放行交由安全链 401
                : currentSession(sessionId, sessionJson)
                    .flatMap(session -> inject(exchange, chain, session)))
            .onErrorResume(e -> {
                log.warn("Session auth filter error, pass-through without injection", e);
                return chain.filter(exchange);
            });
    }

    /** 解析 session JSON；临期（剩余 &lt;60s）时经 IAM 懒刷新（reuse 不轮换，并发最后写赢，无锁安全） */
    private Mono<JsonNode> currentSession(String sessionId, String sessionJson) {
        return Mono.fromCallable(() -> objectMapper.readTree(sessionJson))
            .flatMap(session -> {
                long expireAt = session.path("expire_at").asLong(0);
                if (expireAt - System.currentTimeMillis() > REFRESH_THRESHOLD_MS) {
                    return Mono.just(session);
                }
                return refresh(sessionId).defaultIfEmpty(session);
            });
    }

    private Mono<Void> inject(ServerWebExchange exchange, WebFilterChain chain, JsonNode session) {
        String accessToken = session.path("access_token").asText("");
        log.info("SessionAuth inject: path={}, token={}...", exchange.getRequest().getPath(),
            accessToken.substring(0, Math.min(12, accessToken.length())));
        if (accessToken.isEmpty()) {
            return chain.filter(exchange);
        }
        // 移除入站 Authorization 防伪造，再注入服务端 token
        ServerWebExchange mutated = exchange.mutate()
            .request(b -> b.headers(h -> {
                h.remove(HttpHeaders.AUTHORIZATION);
                h.setBearerAuth(accessToken);
            }))
            .build();
        return chain.filter(mutated);
    }

    private Mono<JsonNode> refresh(String sessionId) {
        return webClient.post()
            .uri(URI.create(iamBaseUrl + "/auth/refresh"))
            .contentType(MediaType.APPLICATION_JSON)
            .cookie(SESSION_COOKIE, sessionId)
            .retrieve()
            .bodyToMono(String.class)
            .map(body -> {
                try {
                    return objectMapper.readTree(body);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            })
            .onErrorResume(e -> {
                log.warn("BFF token refresh failed for session {}: {}", sessionId, e.getMessage());
                return Mono.empty();
            });
    }

    @Override
    public int getOrder() {
        // 必须先于网关 SecurityWebFilterChain 的 WebFilterChainProxy（默认 -100），
        // 注入的 Authorization 头随后由安全链 introspection 认证
        return -200;
    }
}
