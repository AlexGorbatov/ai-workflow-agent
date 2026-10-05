package com.altronixsoft.workflow.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Bearer tokens from Keycloak. Roles come from {@code realm_access.roles} ({@code operator}, {@code approver})
 * and become {@code ROLE_operator} and {@code ROLE_approver}; the user's name is {@code preferred_username}.
 * Approvals are for approvers; instances for operators and approvers (a model call's full text for operators
 * only); health and the static UI are public.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String OPERATOR = "operator";
    static final String APPROVER = "approver";

    static final String[] PUBLIC_GET_ENDPOINTS = {
        "/actuator/health", "/actuator/health/**", "/", "/index.html", "/ui/**", "/favicon.ico"
    };

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, PUBLIC_GET_ENDPOINTS)
                        .permitAll()
                        .requestMatchers("/api/v1/approvals/**", "/api/v1/approvals")
                        .hasRole(APPROVER)
                        // a model call's full text includes the customer's email: operators only
                        .requestMatchers("/api/v1/instances/*/llm-calls/**")
                        .hasRole(OPERATOR)
                        .requestMatchers("/api/v1/instances/**", "/api/v1/instances")
                        .hasAnyRole(OPERATOR, APPROVER)
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthentication())))
                // Bearer tokens only: no session and no cookies, so CSRF has nothing to protect.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }

    static JwtAuthenticationConverter jwtAuthentication() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new RealmRoles());
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }

    /** {@code realm_access.roles} as {@code ROLE_<role>}; a token without it has no roles. */
    static final class RealmRoles implements Converter<Jwt, Collection<GrantedAuthority>> {

        @Override
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            Object realmAccess = jwt.getClaims().get("realm_access");
            if (!(realmAccess instanceof Map<?, ?> access) || !(access.get("roles") instanceof Collection<?> roles)) {
                return List.of();
            }
            return roles.stream()
                    .filter(String.class::isInstance)
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        }
    }
}
