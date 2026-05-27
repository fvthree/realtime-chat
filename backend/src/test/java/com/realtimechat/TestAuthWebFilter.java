package com.realtimechat;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.WebFilter;

import java.util.List;

/**
 * Test-only WebFilter that reads the X-Auth-User header and injects a mock
 * authentication into the reactive security context before the security filter chain runs.
 *
 * Usage: add "X-Auth-User: testuser" to WebSocket or HTTP headers in tests.
 * This replaces the live GitHub OAuth2 flow in integration tests.
 *
 * Must run at HIGHEST_PRECEDENCE so it fires before Spring Security's WebFilterChainProxy
 * (which is at SecurityProperties.DEFAULT_FILTER_ORDER = -100). Without this ordering,
 * Spring Security rejects unauthenticated requests before our header is read.
 */
@TestConfiguration
public class TestAuthWebFilter {

    static final String HEADER = "X-Auth-User";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public WebFilter testAuthWebFilter() {
        return (exchange, chain) -> {
            String user = exchange.getRequest().getHeaders().getFirst(HEADER);
            if (user == null || user.isBlank()) {
                return chain.filter(exchange);
            }
            var auth = new UsernamePasswordAuthenticationToken(
                    user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            return chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
        };
    }
}
