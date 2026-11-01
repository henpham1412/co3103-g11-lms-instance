package vn.edu.hcmut.co3103.lms.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import vn.edu.hcmut.co3103.lms.security.JsonAccessDeniedHandler;
import vn.edu.hcmut.co3103.lms.security.JsonAuthenticationEntryPoint;
import vn.edu.hcmut.co3103.lms.security.JsonCorsFilter;
import vn.edu.hcmut.co3103.lms.security.jwt.ClaimValidatingJwtDecoder;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtSubjectValidator;
import vn.edu.hcmut.co3103.lms.security.jwt.LocalAccessJwtAuthenticationConverter;
import java.net.URI;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    Clock jwtClock() {
        return Clock.systemUTC();
    }

    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            Clock jwtClock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(List.of(
                new JwtIssuerValidator(issuer), new JwtSubjectValidator())));
        return new ClaimValidatingJwtDecoder(decoder, jwtClock);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${lms.allowed-origins:http://localhost:3000,http://localhost:5173}") String allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",", -1))
                .map(String::trim).map(origin -> validateOrigin(origin)).distinct().toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "Origin",
                "X-Requested-With", "Access-Control-Request-Method", "Access-Control-Request-Headers"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static String validateOrigin(String origin) {
        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("lms.allowed-origins must contain valid HTTP(S) origins.", invalid);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null
                || origin.contains("*") || uri.getRawAuthority().endsWith(":")
                || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
            throw new IllegalArgumentException("lms.allowed-origins must contain HTTP(S) origins with a host, "
                    + "an optional port (1-65535), and no userinfo, path, query, fragment, or wildcard.");
        }
        return origin;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonAuthenticationEntryPoint entryPoint,
                                           JsonAccessDeniedHandler accessDeniedHandler,
                                           LocalAccessJwtAuthenticationConverter jwtAuthenticationConverter,
                                           CorsConfigurationSource corsConfigurationSource) {
        http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .addFilterAt(new JsonCorsFilter(corsConfigurationSource, accessDeniedHandler), CorsFilter.class)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        // Let internal dispatches render safe JSON errors without a false 401.
                        // Direct requests to /error still require an active local role below.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // A valid JWT establishes identity; an active local role grants LMS access.
                        .anyRequest().hasAnyRole("STUDENT", "TUTOR", "ADMINISTRATOR")
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                );
        return http.build();
    }
}
