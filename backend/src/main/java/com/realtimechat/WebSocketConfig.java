package com.realtimechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Configuration
public class WebSocketConfig {

    @Value("${app.cors-allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String corsAllowedOriginsRaw;

    @Bean
    public ChatWebSocketHandler chatWebSocketHandler(
            RoomRegistry registry,
            ObjectMapper json,
            MessageRepository messageRepository,
            RateLimiterService rateLimiterService
    ) {
        return new ChatWebSocketHandler(registry, json, messageRepository, rateLimiterService);
    }

    @Bean
    public HandlerMapping webSocketMapping(ChatWebSocketHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of("/ws/chat/**", handler));
        mapping.setOrder(-1);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        return new WebSocketHandlerAdapter();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        List<String> origins = Arrays.stream(corsAllowedOriginsRaw.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(java.util.List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(java.util.List.of("*"));
        cors.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }

}
