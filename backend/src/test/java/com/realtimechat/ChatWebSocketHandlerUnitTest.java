package com.realtimechat;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level tests for ChatWebSocketHandler helper methods that don't require
 * a full Spring Boot or Testcontainers context.
 */
class ChatWebSocketHandlerUnitTest {

    private final ChatWebSocketHandler handler =
            new ChatWebSocketHandler(null, null, null, null);

    private String extractLogin(Principal principal) throws Exception {
        Method m = ChatWebSocketHandler.class.getDeclaredMethod("extractLogin", Principal.class);
        m.setAccessible(true);
        return (String) m.invoke(handler, principal);
    }

    @Test
    void extractLoginReturnsLoginAttributeFromOAuth2Token() throws Exception {
        var user = new DefaultOAuth2User(List.of(),
                Map.of("login", "fvthree", "id", 1), "login");
        var token = new OAuth2AuthenticationToken(user, List.of(), "github");
        assertThat(extractLogin(token)).isEqualTo("fvthree");
    }

    @Test
    void extractLoginFallsBackToGetNameWhenLoginAttributeIsBlank() throws Exception {
        // OAuth2 token where the "login" attribute is blank — should fall back to getName()
        var user = new DefaultOAuth2User(List.of(),
                Map.of("login", "   ", "id", 1, "name", "fallback-name"), "name");
        var token = new OAuth2AuthenticationToken(user, List.of(), "github");
        // DefaultOAuth2User.getName() returns the attribute named by the nameAttributeKey ("name")
        assertThat(extractLogin(token)).isEqualTo("fallback-name");
    }

    @Test
    void extractLoginFallsBackToGetNameForNonOAuth2Principal() throws Exception {
        var token = new UsernamePasswordAuthenticationToken("test-user", null);
        assertThat(extractLogin(token)).isEqualTo("test-user");
    }
}
