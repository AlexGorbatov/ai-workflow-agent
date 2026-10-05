package com.altronixsoft.workflow.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class RealmRolesTest {

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("f3b1c2d4")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(60));
        claims.forEach(builder::claim);
        return builder.build();
    }

    @Test
    void realmRolesBecomeSpringRolesAndTheNameIsTheUsername() {
        Jwt token = jwt(Map.of(
                "preferred_username",
                "max",
                "realm_access",
                Map.of("roles", List.of("operator", "approver", "default-roles-workflow"))));

        JwtAuthenticationToken auth =
                (JwtAuthenticationToken) SecurityConfig.jwtAuthentication().convert(token);

        assertThat(auth.getName()).isEqualTo("max");
        assertThat(roles(auth.getAuthorities()))
                .containsExactlyInAnyOrder("ROLE_operator", "ROLE_approver", "ROLE_default-roles-workflow");
    }

    @Test
    void aTokenWithoutRealmRolesHasNoRoles() {
        Jwt token = jwt(Map.of("preferred_username", "eve", "scope", "openid profile"));

        assertThat(roles(SecurityConfig.jwtAuthentication().convert(token).getAuthorities()))
                .isEmpty();
    }

    /** Spring Security also adds a factor authority (FACTOR_BEARER); only the roles matter here. */
    private static List<String> roles(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .toList();
    }
}
