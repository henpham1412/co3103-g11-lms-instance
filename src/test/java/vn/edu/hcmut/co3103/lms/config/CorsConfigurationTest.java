package vn.edu.hcmut.co3103.lms.config;

import jakarta.servlet.Filter;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import tools.jackson.databind.ObjectMapper;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.security.JsonAccessDeniedHandler;
import vn.edu.hcmut.co3103.lms.security.JsonAuthenticationEntryPoint;
import vn.edu.hcmut.co3103.lms.security.JsonCorsFilter;
import vn.edu.hcmut.co3103.lms.security.jwt.LocalAccessJwtAuthenticationConverter;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class CorsConfigurationTest {

    @Test
    void defaultsAllowExactlyTheDocumentedLocalOrigins() {
        try (var context = context(null, false)) {
            context.refresh();
            CorsConfiguration policy = policy(context);
            assertThat(policy.getAllowedOrigins()).containsExactly("http://localhost:3000", "http://localhost:5173");
            assertThat(policy.getAllowedOriginPatterns()).isNullOrEmpty();
            assertThat(policy.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
            assertThat(policy.getAllowCredentials()).isTrue();
            assertThat(policy.getMaxAge()).isEqualTo(3600L);
        }
    }

    @Test
    void trimsAndDeduplicatesOverridesWithoutAppendingDefaults() {
        try (var context = context(" https://portal.example.test , http://[::1]:5173, https://portal.example.test ", false)) {
            context.refresh();
            assertThat(policy(context).getAllowedOrigins())
                    .containsExactly("https://portal.example.test", "http://[::1]:5173");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ",", "https://portal.example.test,", ",https://portal.example.test",
            "https://portal.example.test,,https://other.example.test", "*", "null", "https://*.example.test",
            "ftp://portal.example.test", "portal.example.test", "https:///missing-host", "https://user@host.test",
            "https://portal.example.test/", "https://portal.example.test/path", "https://portal.example.test?q=1",
            "https://portal.example.test#fragment", "https://portal.example.test:", "https://portal.example.test:0",
            "https://portal.example.test:-1", "https://portal.example.test:65536", "https://portal.example.test:abc"})
    void invalidOriginsFailStartup(String origins) {
        try (var context = context(origins, false)) {
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("lms.allowed-origins");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usesExactlyOneJsonCorsFilterBeforeAuthenticationEvenWithEarlySource(boolean early) throws Exception {
        String origin = "https://portal.example.test";
        try (var context = context(origin, early)) {
            context.refresh();
            var chain = context.getBean(SecurityFilterChain.class);
            var corsFilters = chain.getFilters().stream().filter(CorsFilter.class::isInstance).toList();
            assertThat(corsFilters).singleElement().isInstanceOf(JsonCorsFilter.class);
            var bearer = chain.getFilters().stream().filter(BearerTokenAuthenticationFilter.class::isInstance)
                    .findFirst().orElseThrow();
            assertThat(chain.getFilters().indexOf(corsFilters.getFirst())).isLessThan(chain.getFilters().indexOf(bearer));
            assertThat(context.getBeansOfType(CorsConfigurationSource.class)).hasSize(1);
            assertThat(context.getBeansOfType(Filter.class).values()).noneMatch(CorsFilter.class::isInstance);

            var accepted = request(context, "OPTIONS", origin);
            assertThat(accepted.getStatus()).isEqualTo(200);
            assertThat(accepted.getHeaders(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).containsExactly(origin);
            assertThat(accepted.getHeaders(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).containsExactly("true");
            assertThat(accepted.getHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE)).isEqualTo("3600");
            assertThat(accepted.getContentAsString()).isEmpty();

            for (String denied : new String[] {"http://localhost:3000", "http://localhost:5173"}) {
                var response = request(context, "OPTIONS", denied);
                assertJsonError(response, 403);
                assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
                assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
            }
            var unauthenticated = request(context, "GET", origin);
            assertJsonError(unauthenticated, 401);
            assertThat(unauthenticated.getHeaders(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).containsExactly(origin);
            assertThat(unauthenticated.getHeaders(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).containsExactly("true");
            verifyNoInteractions(context.getBean(LmsUserAccessRepository.class));
        }
    }

    private CorsConfiguration policy(AnnotationConfigWebApplicationContext context) {
        return context.getBean(CorsConfigurationSource.class)
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/probe"));
    }

    private MockHttpServletResponse request(AnnotationConfigWebApplicationContext context, String method, String origin)
            throws Exception {
        var request = new MockHttpServletRequest(context.getServletContext(), method, "/probe");
        request.addHeader(HttpHeaders.ORIGIN, origin);
        if (method.equals("OPTIONS")) {
            request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
            request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type");
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid-token");
        }
        var response = new MockHttpServletResponse();
        new FilterChainProxy(context.getBean(SecurityFilterChain.class)).doFilter(request, response,
                (req, res) -> { throw new AssertionError("Request must not reach the application"); });
        return response;
    }

    private void assertJsonError(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        var body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.get("code").asString()).isEqualTo(status == 401 ? "UNAUTHORIZED" : "FORBIDDEN");
        assertThat(body.get("message").asString()).isEqualTo(status == 401
                ? "A valid Bearer access token is required." : "Access to this resource is denied.");
        assertThat(body.get("details").isArray()).isTrue();
        assertThat(body.get("details").isEmpty()).isTrue();
    }

    private AnnotationConfigWebApplicationContext context(String origins, boolean early) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        Map<String, Object> properties = new HashMap<>(Map.of(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://identity.example.test",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri", "https://identity.example.test/jwks"));
        if (origins != null) properties.put("lms.allowed-origins", origins);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(SecurityConfig.class, Dependencies.class, JsonAccessDeniedHandler.class,
                JsonAuthenticationEntryPoint.class, LocalAccessJwtAuthenticationConverter.class);
        if (early) context.addBeanFactoryPostProcessor(factory -> {
            // Resolve @Value before deliberately instantiating the source ahead of HttpSecurity.
            factory.addEmbeddedValueResolver(context.getEnvironment()::resolveRequiredPlaceholders);
            factory.getBean("corsConfigurationSource");
        });
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean Validator validator() { return mock(Validator.class); }
        @Bean LmsUserAccessRepository users() { return mock(LmsUserAccessRepository.class); }
    }
}
