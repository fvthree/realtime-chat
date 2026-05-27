package com.realtimechat;

import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
public class ChatController {

    @GetMapping("/api/me")
    public Mono<Map<String, String>> me(OAuth2AuthenticationToken auth) {
        var attrs = auth.getPrincipal().getAttributes();
        String login = (String) attrs.getOrDefault("login", "");
        String avatarUrl = (String) attrs.getOrDefault("avatar_url", "");
        return Mono.just(Map.of("login", login, "avatarUrl", avatarUrl));
    }
}
