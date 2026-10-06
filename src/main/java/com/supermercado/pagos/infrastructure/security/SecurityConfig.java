package com.supermercado.pagos.infrastructure.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * pedidos-service es un OAuth2 Resource Server: no muestra pantalla de login,
 * solo valida los tokens que emite Keycloak (ver issuer-uri en application.yml).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) {
        http
                .csrf(csrf -> csrf.disable()) // API stateless sin cookies de sesion: no aplica CSRF
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/pagos/**").hasRole("cliente")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
        return http.build();
    }

    /**
     * Keycloak expone los roles del realm en el claim "realm_access.roles",
     * que Spring Security no interpreta de forma nativa (eso es especifico de
     * Keycloak, no un estandar OIDC). Este converter traduce ese claim al
     * formato de autoridades que Spring entiende (prefijo "ROLE_").
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess == null || realmAccess.get("roles") == null) {
                return List.<GrantedAuthority>of();
            }
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) realmAccess.get("roles");
            Collection<GrantedAuthority> authorities = roles.stream()
                    .<GrantedAuthority>map(rol -> new SimpleGrantedAuthority("ROLE_" + rol))
                    .toList();
            return authorities;
        });
        return converter;
    }
}
