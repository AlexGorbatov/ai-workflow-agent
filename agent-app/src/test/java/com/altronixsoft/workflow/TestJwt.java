package com.altronixsoft.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Bearer tokens without Keycloak: the token is a user name, decoded into a JWT shaped like Keycloak's
 * ({@code preferred_username}, {@code realm_access.roles}). Requests then pass the real filter chain and role
 * mapping. Users as in keycloak/workflow-realm.json, plus one approver-only and one without roles.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwt {

    /** operator */
    public static final String OLENA = "olena";
    /** operator and approver */
    public static final String MAX = "max";
    /** approver only */
    public static final String IVAN = "ivan";
    /** no roles */
    public static final String EVE = "eve";

    private static final Map<String, List<String>> ROLES = Map.of(
            OLENA, List.of("operator"),
            MAX, List.of("operator", "approver"),
            IVAN, List.of("approver"),
            EVE, List.of());

    public static String bearer(String user) {
        return "Bearer " + user;
    }

    @Bean
    JwtDecoder testJwtDecoder() {
        return token -> {
            List<String> roles = ROLES.get(token);
            if (roles == null) {
                throw new BadJwtException("Unknown test token");
            }
            Instant now = Instant.parse("2026-10-05T10:00:00Z");
            return Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("sub-" + token)
                    .claim("preferred_username", token)
                    .claim("realm_access", Map.of("roles", roles))
                    .issuedAt(now)
                    .expiresAt(now.plusSeconds(3600))
                    .build();
        };
    }
}
