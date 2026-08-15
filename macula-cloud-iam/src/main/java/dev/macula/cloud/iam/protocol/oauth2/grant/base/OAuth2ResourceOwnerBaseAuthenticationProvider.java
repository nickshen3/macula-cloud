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

package dev.macula.cloud.iam.protocol.oauth2.grant.base;

import dev.macula.cloud.iam.utils.OAuth2ErrorCodesExpand;
import dev.macula.cloud.iam.utils.ScopeException;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.SpringSecurityMessageSource;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.token.DefaultOAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code OAuth2ResourceOwnerBaseAuthenticationProvider} GrantType扩展基类
 *
 * @author jumuning
 *
 *     处理自定义授权
 */
@Slf4j
public abstract class OAuth2ResourceOwnerBaseAuthenticationProvider<T extends OAuth2ResourceOwnerBaseAuthenticationToken>
    implements AuthenticationProvider, MessageSourceAware {

    private ApplicationContext applicationContext;
    private DaoAuthenticationProvider lazyDaoProvider;
    private StringRedisTemplate rateLimitRedis;

    /** 登录失败锁定阈值（账号/IP 双维度各自计数） */
    private static final int LOGIN_MAX_FAIL = 5;
    /** 锁定时长（秒） */
    private static final long LOGIN_LOCK_SECONDS = 600;
    private static final String LOGIN_FAIL_KEY_PREFIX = "login:fail:";

    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    private static final Logger LOGGER = LogManager.getLogger(OAuth2ResourceOwnerBaseAuthenticationProvider.class);

    private static final String ERROR_URI = "https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1";

    private final OAuth2AuthorizationService authorizationService;

    private final OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator;

    private final AuthenticationManager authenticationManager;

    protected MessageSourceAccessor messages = SpringSecurityMessageSource.getAccessor();

    /**
     * Constructs an {@code OAuth2AuthorizationCodeAuthenticationProvider} using the provided parameters.
     *
     * @param authenticationManager the authentication manager
     * @param authorizationService  the authorization service
     * @param tokenGenerator        the token generator
     * @since 0.2.3
     */
    public OAuth2ResourceOwnerBaseAuthenticationProvider(AuthenticationManager authenticationManager,
        OAuth2AuthorizationService authorizationService, OAuth2TokenGenerator<? extends OAuth2Token> tokenGenerator) {
        Assert.notNull(authorizationService, "authorizationService cannot be null");
        Assert.notNull(tokenGenerator, "tokenGenerator cannot be null");
        this.authenticationManager = authenticationManager;
        this.authorizationService = authorizationService;
        this.tokenGenerator = tokenGenerator;
    }

    private Authentication getAuthenticationFromManager(Authentication authenticationToken) {
        try {
            return authenticationManager.authenticate(authenticationToken);
        } catch (Exception e) {
            LOGGER.warn("authenticationManager.authenticate failed: " + e.getClass().getName() + ": " + e.getMessage());
            // 延迟创建 DaoAuthenticationProvider
            if (lazyDaoProvider == null && applicationContext != null) {
                try {
                    LOGGER.info("Attempting to create DaoAuthenticationProvider from ApplicationContext");
                    org.springframework.security.core.userdetails.UserDetailsService uds =
                        (org.springframework.security.core.userdetails.UserDetailsService)
                        applicationContext.getBean("sysUserDetailsService");
                    org.springframework.security.crypto.password.PasswordEncoder pe =
                        applicationContext.getBean(org.springframework.security.crypto.password.PasswordEncoder.class);
                    lazyDaoProvider = new DaoAuthenticationProvider();
                    lazyDaoProvider.setUserDetailsService(uds);
                    lazyDaoProvider.setPasswordEncoder(pe);
                    LOGGER.info("DaoAuthenticationProvider created successfully");
                } catch (Exception ex) {
                    LOGGER.error("Failed to create DaoAuthenticationProvider: " + ex.getMessage(), ex);
                    throw e;
                }
            }
            if (lazyDaoProvider != null) {
                return lazyDaoProvider.authenticate(authenticationToken);
            }
            throw e;
        }
    }

    // ==================== P0-5 登录防爆破限流 ====================

    private StringRedisTemplate rateLimitRedis() {
        if (rateLimitRedis == null && applicationContext != null) {
            try {
                rateLimitRedis = applicationContext.getBean(StringRedisTemplate.class);
            } catch (Exception ex) {
                LOGGER.warn("StringRedisTemplate unavailable, login rate-limit disabled: " + ex.getMessage());
            }
        }
        return rateLimitRedis;
    }

    private void checkLoginLock(String principal) {
        StringRedisTemplate redis = rateLimitRedis();
        if (redis == null) {
            return;
        }
        try {
            if (failCount(redis, "acct", principal) >= LOGIN_MAX_FAIL
                || failCount(redis, "ip", getClientIp()) >= LOGIN_MAX_FAIL) {
                LOGGER.warn("Login locked: principal=" + principal + " ip=" + getClientIp());
                throw new OAuth2AuthenticationException(
                    new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED, "登录失败次数过多，请 10 分钟后再试", null));
            }
        } catch (OAuth2AuthenticationException e) {
            throw e;
        } catch (Exception ex) {
            LOGGER.warn("login rate-limit check error (fail-open): " + ex.getMessage());
        }
    }

    private void recordLoginFail(String principal) {
        StringRedisTemplate redis = rateLimitRedis();
        if (redis == null) {
            return;
        }
        try {
            incrFail(redis, "acct", principal);
            incrFail(redis, "ip", getClientIp());
        } catch (Exception ex) {
            LOGGER.warn("login fail count error: " + ex.getMessage());
        }
    }

    private void clearLoginFail(String principal) {
        StringRedisTemplate redis = rateLimitRedis();
        if (redis == null) {
            return;
        }
        try {
            // 仅清账号维度；IP 维度自然过期，避免误清同 IP 其他账号的失败记录
            redis.delete(LOGIN_FAIL_KEY_PREFIX + "acct:" + principal);
        } catch (Exception ex) {
            LOGGER.warn("login fail clear error: " + ex.getMessage());
        }
    }

    private long failCount(StringRedisTemplate redis, String dim, String val) {
        String v = redis.opsForValue().get(LOGIN_FAIL_KEY_PREFIX + dim + ":" + val);
        if (v == null) {
            return 0;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void incrFail(StringRedisTemplate redis, String dim, String val) {
        String key = LOGIN_FAIL_KEY_PREFIX + dim + ":" + val;
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1) {
            redis.expire(key, Duration.ofSeconds(LOGIN_LOCK_SECONDS));
        }
    }

    private String getClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes)RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                String xff = attrs.getRequest().getHeader("X-Forwarded-For");
                if (xff != null && !xff.isBlank()) {
                    return xff.split(",")[0].trim();
                }
                return attrs.getRequest().getRemoteAddr();
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    public abstract AbstractAuthenticationToken buildToken(Map<String, Object> reqParameters);

    /**
     * 当前provider是否支持此令牌类型
     *
     * @param authentication 认证类
     * @return 是否支持
     */
    @Override
    public abstract boolean supports(Class<?> authentication);

    /**
     * 当前的请求客户端是否支持此模式
     *
     * @param registeredClient 客户端
     */
    public abstract void checkClient(RegisteredClient registeredClient);

    /**
     * Performs authentication with the same contract as {@link AuthenticationManager#authenticate(Authentication)} .
     *
     * @param authentication the authentication request object.
     * @return a fully authenticated object including credentials. May return
     *     <code>null</code> if the <code>AuthenticationProvider</code> is unable to support
     *     authentication of the passed <code>Authentication</code> object. In such a case, the next
     *     <code>AuthenticationProvider</code> that supports the presented
     *     <code>Authentication</code> class will be tried.
     * @throws AuthenticationException if authentication fails.
     */
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {

        T resourceOwnerBaseAuthentication = (T)authentication;

        OAuth2ClientAuthenticationToken clientPrincipal =
            getAuthenticatedClientElseThrowInvalidClient(resourceOwnerBaseAuthentication);

        RegisteredClient registeredClient = clientPrincipal.getRegisteredClient();
        checkClient(registeredClient);

        Set<String> authorizedScopes;
        // Default to configured scopes
        if (!CollectionUtils.isEmpty(resourceOwnerBaseAuthentication.getScopes())) {
            for (String requestedScope : resourceOwnerBaseAuthentication.getScopes()) {
                if (registeredClient == null || !registeredClient.getScopes().contains(requestedScope)) {
                    throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_SCOPE);
                }
            }
            authorizedScopes = new LinkedHashSet<>(resourceOwnerBaseAuthentication.getScopes());
        } else {
            throw new ScopeException(OAuth2ErrorCodesExpand.SCOPE_IS_EMPTY);
        }

        Map<String, Object> reqParameters = resourceOwnerBaseAuthentication.getAdditionalParameters();
        try {

            AbstractAuthenticationToken authenticationToken = buildToken(reqParameters);

            LOGGER.debug("got authenticationToken=" + authenticationToken);

            // P0-5 登录防爆破：账号/IP 双维度限流，5 次失败锁定 10 分钟
            String loginPrincipal = String.valueOf(authenticationToken.getPrincipal());
            checkLoginLock(loginPrincipal);

            Authentication usernamePasswordAuthentication;
            try {
                usernamePasswordAuthentication = getAuthenticationFromManager(authenticationToken);
            } catch (BadCredentialsException | InternalAuthenticationServiceException e) {
                recordLoginFail(loginPrincipal);
                throw e;
            }
            clearLoginFail(loginPrincipal);

            // @formatter:off
            DefaultOAuth2TokenContext.Builder tokenContextBuilder = DefaultOAuth2TokenContext.builder()
                    .registeredClient(registeredClient)
                    .principal(usernamePasswordAuthentication)
                    .authorizationServerContext(AuthorizationServerContextHolder.getContext())
                    .authorizedScopes(authorizedScopes)
                    .authorizationGrantType(AuthorizationGrantType.PASSWORD)
                    .authorizationGrant(resourceOwnerBaseAuthentication);
            // @formatter:on

            OAuth2Authorization.Builder authorizationBuilder =
                OAuth2Authorization.withRegisteredClient(registeredClient)
                    .principalName(usernamePasswordAuthentication.getName())
                    .authorizationGrantType(AuthorizationGrantType.PASSWORD)
                    // 0.4.0 新增的方法
                    .authorizedScopes(authorizedScopes);

            // ----- Access token -----
            OAuth2TokenContext tokenContext = tokenContextBuilder.tokenType(OAuth2TokenType.ACCESS_TOKEN).build();
            OAuth2Token generatedAccessToken = this.tokenGenerator.generate(tokenContext);
            if (generatedAccessToken == null) {
                OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR,
                    "The token generator failed to generate the access token.", ERROR_URI);
                throw new OAuth2AuthenticationException(error);
            }
            OAuth2AccessToken accessToken =
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, generatedAccessToken.getTokenValue(),
                    generatedAccessToken.getIssuedAt(), generatedAccessToken.getExpiresAt(),
                    tokenContext.getAuthorizedScopes());
            if (generatedAccessToken instanceof ClaimAccessor) {
                authorizationBuilder.id(accessToken.getTokenValue()).token(accessToken,
                        (metadata) -> metadata.put(OAuth2Authorization.Token.CLAIMS_METADATA_NAME,
                            ((ClaimAccessor)generatedAccessToken).getClaims()))
                    // 0.4.0 新增的方法
                    .authorizedScopes(authorizedScopes)
                    .attribute(Principal.class.getName(), usernamePasswordAuthentication);
            } else {
                authorizationBuilder.id(accessToken.getTokenValue()).accessToken(accessToken);
            }

            // ----- Refresh token -----
            OAuth2RefreshToken refreshToken = null;
            if (registeredClient.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN) &&
                // Do not issue refresh token to public client
                !clientPrincipal.getClientAuthenticationMethod().equals(ClientAuthenticationMethod.NONE)) {

                tokenContext = tokenContextBuilder.tokenType(OAuth2TokenType.REFRESH_TOKEN).build();
                OAuth2Token generatedRefreshToken = this.tokenGenerator.generate(tokenContext);
                if (!(generatedRefreshToken instanceof OAuth2RefreshToken)) {
                    OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR,
                        "The token generator failed to generate the refresh token.", ERROR_URI);
                    throw new OAuth2AuthenticationException(error);
                }
                refreshToken = (OAuth2RefreshToken)generatedRefreshToken;
            }
            authorizationBuilder.refreshToken(refreshToken);

            OAuth2Authorization authorization = authorizationBuilder.build();

            this.authorizationService.save(authorization);

            LOGGER.debug("returning OAuth2AccessTokenAuthenticationToken");

            return new OAuth2AccessTokenAuthenticationToken(registeredClient, clientPrincipal, accessToken,
                refreshToken, Objects.requireNonNull(authorization.getAccessToken().getClaims()));

        } catch (Exception ex) {
            if (ex instanceof AuthenticationException) {
                throw oAuth2AuthenticationException(authentication, (AuthenticationException)ex);
            }
            throw oAuth2AuthenticationException(authentication, new BadCredentialsException("unknown auth error", ex));
        }

    }

    public void setMessageSource(MessageSource messageSource) {
        this.messages = new MessageSourceAccessor(messageSource);
    }

    /**
     * 登录异常转换为oauth2异常
     *
     * @param authentication          身份验证
     * @param authenticationException 身份验证异常
     * @return {@link OAuth2AuthenticationException}
     */
    private OAuth2AuthenticationException oAuth2AuthenticationException(Authentication authentication,
        AuthenticationException authenticationException) {
        // 已是 OAuth2 标准错误（如登录限流锁定）直接透传，避免被包装成 server_error
        if (authenticationException instanceof OAuth2AuthenticationException) {
            return (OAuth2AuthenticationException)authenticationException;
        }
        if (authenticationException instanceof UsernameNotFoundException) {
            return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodesExpand.USERNAME_NOT_FOUND,
                this.messages.getMessage("JdbcDaoImpl.notFound", new Object[] {authentication.getName()},
                    "Username {0} not found"), ""));
        }
        if (authenticationException instanceof BadCredentialsException) {
            return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodesExpand.BAD_CREDENTIALS,
                this.messages.getMessage("AbstractUserDetailsAuthenticationProvider.badCredentials", "Bad credentials"),
                ""));
        }
        if (authenticationException instanceof LockedException) {
            return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodesExpand.USER_LOCKED,
                this.messages.getMessage("AbstractUserDetailsAuthenticationProvider.locked", "User account is locked"),
                ""));
        }
        if (authenticationException instanceof DisabledException) {
            return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodesExpand.USER_DISABLE,
                this.messages.getMessage("AbstractUserDetailsAuthenticationProvider.disabled", "User is disabled"),
                ""));
        }
        if (authenticationException instanceof AccountExpiredException) {
            return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodesExpand.USER_EXPIRED,
                this.messages.getMessage("AbstractUserDetailsAuthenticationProvider.expired",
                    "User account has expired"), ""));
        }
        if (authenticationException instanceof CredentialsExpiredException) {
            return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodesExpand.CREDENTIALS_EXPIRED,
                this.messages.getMessage("AbstractUserDetailsAuthenticationProvider.credentialsExpired",
                    "User credentials have expired"), ""));
        }
        if (authenticationException instanceof ScopeException) {
            return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_SCOPE,
                this.messages.getMessage("AbstractAccessDecisionManager.accessDenied", "invalid_scope"), ""));
        }

        log.error(authenticationException.getLocalizedMessage());
        return new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR),
            authenticationException.getLocalizedMessage(), authenticationException);
    }

    private OAuth2ClientAuthenticationToken getAuthenticatedClientElseThrowInvalidClient(
        Authentication authentication) {

        OAuth2ClientAuthenticationToken clientPrincipal = null;

        if (OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication.getPrincipal().getClass())) {
            clientPrincipal = (OAuth2ClientAuthenticationToken)authentication.getPrincipal();
        }

        if (clientPrincipal != null && clientPrincipal.isAuthenticated()) {
            return clientPrincipal;
        }

        throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
    }

}
