package vn.edu.hcmut.co3103.lms.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import vn.edu.hcmut.co3103.lms.security.JsonAccessDeniedHandler;
import vn.edu.hcmut.co3103.lms.security.JsonAuthenticationEntryPoint;
import vn.edu.hcmut.co3103.lms.security.jwt.ClaimValidatingJwtDecoder;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtSubjectValidator;
import vn.edu.hcmut.co3103.lms.security.jwt.LocalAccessJwtAuthenticationConverter;

import java.time.Clock;
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
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonAuthenticationEntryPoint entryPoint,
                                           JsonAccessDeniedHandler accessDeniedHandler,
                                           LocalAccessJwtAuthenticationConverter jwtAuthenticationConverter) {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        // Let Tomcat render infrastructure failures at the original URI without a false 401.
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
