package com.realtimechat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketConfigTest {

    private WebSocketConfig configWith(String rawOrigins) throws Exception {
        WebSocketConfig cfg = new WebSocketConfig();
        Field f = WebSocketConfig.class.getDeclaredField("corsAllowedOriginsRaw");
        f.setAccessible(true);
        f.set(cfg, rawOrigins);
        return cfg;
    }

    @Test
    void defaultOriginsIncludeLocalhostVariants() throws Exception {
        CorsConfigurationSource source = configWith(
                "http://localhost:5173,http://127.0.0.1:5173").corsConfigurationSource();
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost:5173/ws/chat/lobby").build());
        var cors = source.getCorsConfiguration(exchange);
        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins())
                .containsExactlyInAnyOrder("http://localhost:5173", "http://127.0.0.1:5173");
        assertThat(cors.getAllowCredentials()).isTrue();
    }

    @Test
    void envVarOriginsAreParsedSplitAndTrimmed() throws Exception {
        CorsConfigurationSource source = configWith(
                "https://staging.example.com , https://prod.example.com , ").corsConfigurationSource();
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("https://staging.example.com/ws/chat/room").build());
        var cors = source.getCorsConfiguration(exchange);
        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins())
                .containsExactlyInAnyOrder("https://staging.example.com", "https://prod.example.com");
    }

    @Test
    void emptyEntriesInCorsStringAreFiltered() throws Exception {
        CorsConfigurationSource source = configWith(
                ",http://localhost:5173,,").corsConfigurationSource();
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost:5173/").build());
        var cors = source.getCorsConfiguration(exchange);
        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins()).containsExactly("http://localhost:5173");
    }
}
