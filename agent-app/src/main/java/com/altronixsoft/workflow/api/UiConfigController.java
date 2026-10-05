package com.altronixsoft.workflow.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Where the UI logs in: the same issuer the API trusts. Public, like the UI itself. */
@RestController
class UiConfigController {

    record UiConfig(String issuer, String clientId) {}

    private final UiConfig config;

    UiConfigController(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${workflow.ui.client-id:workflow-web}") String clientId) {
        this.config = new UiConfig(issuer, clientId);
    }

    @GetMapping("/ui/config.json")
    UiConfig config() {
        return config;
    }
}
