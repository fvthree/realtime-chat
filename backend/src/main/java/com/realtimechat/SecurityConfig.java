package com.realtimechat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity.CsrfSpec;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Value("${app.oauth2-success-redirect}")
    private String successRedirectUrl;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .cors(Customizer.withDefaults())
                .csrf(CsrfSpec::disable)
                .authorizeExchange(ex -> ex
                        .pathMatchers("/oauth2/**", "/login/**").permitAll()
                        .pathMatchers("/api/me", "/ws/chat/**").authenticated()
                        .anyExchange().authenticated()
                )
                .oauth2Login(cfg -> cfg.authenticationSuccessHandler(
                        new RedirectServerAuthenticationSuccessHandler(successRedirectUrl)))
                .build();
    }
}
