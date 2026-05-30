package com.realtimechat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockAuthentication;

@WebFluxTest(ChatController.class)
@Import(SecurityConfig.class)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-secret",
        "app.oauth2-success-redirect=http://localhost:5173/",
})
class ChatControllerTest {

    @Autowired
    WebTestClient webClient;

    private OAuth2AuthenticationToken mockGithubToken(String login, String avatarUrl) {
        OAuth2User principal = new DefaultOAuth2User(
                List.of(),
                Map.of("login", login, "avatar_url", avatarUrl, "id", 12345),
                "login"
        );
        return new OAuth2AuthenticationToken(principal, List.of(), "github");
    }

    @Test
    void getMeReturnsLoginAndAvatarUrl() {
        webClient.mutateWith(mockAuthentication(mockGithubToken("fvthree", "https://avatars.github.com/u/12345")))
                .get().uri("/api/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.login").isEqualTo("fvthree")
                .jsonPath("$.avatarUrl").isEqualTo("https://avatars.github.com/u/12345");
    }

    @Test
    void getMeReturns401WhenUnauthenticated() {
        webClient.get().uri("/api/me")
                .exchange()
                .expectStatus().is3xxRedirection(); // Spring Security redirects to /login
    }
}
