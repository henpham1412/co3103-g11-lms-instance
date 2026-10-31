package vn.edu.hcmut.co3103.lms.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// MockMvc does not perform Tomcat's ERROR redispatch after a security-filter failure.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SecurityErrorDispatchIntegrationTest.ProbeConfiguration.class)
class SecurityErrorDispatchIntegrationTest {

    private static final String ME = "/test/security/error-dispatch/me";
    private static final String PRIVATE_DETAILS =
            "jdbc:postgresql://private-db:5432/lms password=private-password SELECT * FROM lms_user_access";
    private static final JwtTestSupport TOKENS = startTokens();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProbeController controller;

    @MockitoSpyBean
    private LmsUserAccessRepository repository;

    private LmsUserAccess user;

    private static JwtTestSupport startTokens() {
        try {
            return new JwtTestSupport();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> JwtTestSupport.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TOKENS::jwksUri);
    }

    @AfterAll
    static void stopJwksServer() {
        TOKENS.close();
    }

    @BeforeEach
    void setUp() {
        controller.invocations.set(0);
        UUID id = UUID.randomUUID();
        user = repository.saveAndFlush(new LmsUserAccess(id, "Error Dispatch Test", id + "@example.test",
                LmsRole.STUDENT, AccessStatus.ACTIVE, null, null, null));
    }

    @AfterEach
    void cleanUp() {
        reset(repository);
        repository.deleteById(user.getUserId());
    }

    static Stream<Arguments> databaseFailuresAndAcceptHeaders() {
        return Stream.of("application/json", "text/html", "application/xml", "*/*", null,
                        "application/json;q=0", "text/html;q=1, application/json;q=0",
                        "*/*;q=0", "not-a-media-type")
                .flatMap(accept -> Stream.of(
                        Arguments.of(new DataAccessResourceFailureException(PRIVATE_DETAILS), accept),
                        Arguments.of(new CannotCreateTransactionException("Could not open database connection",
                                new SQLException(PRIVATE_DETAILS)), accept)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"http://localhost:3000", "http://localhost:5173"})
    void optionsDoesNotQueryLocalAccessDuringDatabaseOutage(String origin) throws Exception {
        doThrow(new DataAccessResourceFailureException(PRIVATE_DETAILS))
                .when(repository).findById(user.getUserId());
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ME))
                .timeout(Duration.ofSeconds(10)).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(user.getUserId()));
        if (origin != null) {
            request.header(HttpHeaders.ORIGIN, origin)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        }
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEmpty();
            assertThat(response.headers().allValues(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
        }
        verify(repository, never()).findById(any());
        assertThat(controller.invocations).hasValue(0);
    }

    @ParameterizedTest
    @MethodSource("databaseFailuresAndAcceptHeaders")
    void failsClosedWithSafeServerErrorAndRecoversUsingTheSameToken(RuntimeException failure,
                                                                   String accept) throws Exception {
        String token = token(user.getUserId());
        assertSuccessfulLocalAccess(call(ME, token));
        assertThat(controller.invocations).hasValue(1);

        doThrow(failure).when(repository).findById(user.getUserId());
        for (int attempt = 0; attempt < 2; attempt++) {
            var response = call(ME + "?trace=true&message=true&errors=true&exception=true&private=private-query",
                    token, accept);

            assertSafeError(response, 500, "Internal Server Error", ME);
            assertThat(response.body()).doesNotContain(token);
            assertThat(controller.invocations).hasValue(1);
        }
        verify(repository, times(3)).findById(user.getUserId());

        // Remove only the injected failure; recovery must use the real PostgreSQL repository.
        reset(repository);
        assertSuccessfulLocalAccess(call(ME, token));
        assertThat(controller.invocations).hasValue(2);

        user.setAccessStatus(AccessStatus.SUSPENDED);
        repository.saveAndFlush(user);
        assertThat(call(ME, token).statusCode()).isEqualTo(403);
        verify(repository, times(2)).findById(user.getUserId());
        assertThat(call(ME, null).statusCode()).isEqualTo(401);
        assertThat(controller.invocations).hasValue(2);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"application/json", "text/html", "application/xml", "*/*"})
    void directErrorRequestWithoutTokenStillRequiresAuthentication(String accept) throws Exception {
        var response = call("/error", null, accept);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .contains("application/json;charset=UTF-8");
        assertThat(response.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).contains("Bearer");
        assertThat(objectMapper.readTree(response.body()).get("path").asString()).isEqualTo("/error");
        assertThat(controller.invocations).hasValue(0);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"application/json", "text/html", "application/xml", "*/*"})
    void directErrorRequestWithoutActiveLocalRoleIsForbidden(String accept) throws Exception {
        user.setAccessStatus(AccessStatus.SUSPENDED);
        repository.saveAndFlush(user);
        for (UUID id : List.of(user.getUserId(), UUID.randomUUID())) {
            var response = call("/error", token(id), accept);

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                    .contains("application/json;charset=UTF-8");
            assertThat(response.headers().allValues(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
            assertThat(objectMapper.readTree(response.body()).get("path").asString()).isEqualTo("/error");
        }
        assertThat(controller.invocations).hasValue(0);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"application/json", "text/html", "application/xml", "*/*"})
    void directErrorRequestWithActiveLocalRoleHasSafeFallback(String accept) throws Exception {
        assertSafeError(call("/error", token(user.getUserId()), accept),
                500, "Internal Server Error", "/error");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"application/json", "text/html", "application/xml", "*/*"})
    void missingRouteRetainsStatusAndOriginalPath(String accept) throws Exception {
        String path = "/test/security/error-dispatch/missing";
        assertSafeError(call(path, token(user.getUserId()), accept), 404, "Not Found", path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:3000", "http://localhost:5173"})
    void allowedOriginRetainsCorsHeadersOnSafeDatabaseFailure(String origin) throws Exception {
        doThrow(new DataAccessResourceFailureException(PRIVATE_DETAILS))
                .when(repository).findById(user.getUserId());
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + ME))
                .timeout(Duration.ofSeconds(10)).GET()
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(user.getUserId())).build();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertSafeError(response, 500, "Internal Server Error", ME);
            assertThat(response.headers().allValues(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).containsExactly(origin);
            assertThat(response.headers().allValues(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).containsExactly("true");
        }
        verify(repository).findById(user.getUserId());
        assertThat(controller.invocations).hasValue(0);
    }

    private void assertSafeError(HttpResponse<String> response, int status, String error, String path) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().allValues(HttpHeaders.WWW_AUTHENTICATE)).isEmpty();
        assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .contains("application/json;charset=UTF-8");
        assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL).orElseThrow()).contains("no-store");
        assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE)).isEmpty();
        var body = objectMapper.readTree(response.body());
        assertThat(body.size()).isEqualTo(5);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("error").asString()).isEqualTo(error);
        assertThat(body.get("message").asString()).isEqualTo(status >= 500
                ? "An unexpected server error occurred." : error);
        assertThat(body.get("path").asString()).isEqualTo(path);
        assertThat(Instant.parse(body.get("timestamp").asString()))
                .isBetween(Instant.now().minusSeconds(60), Instant.now());
        assertThat(response.body()).doesNotContain("private-db", "private-password", "SELECT", "lms_user_access",
                "Exception", "private-query", "trace");
    }

    private void assertSuccessfulLocalAccess(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(user.getUserId() + "|ROLE_STUDENT");
    }

    private String token(UUID id) throws Exception {
        var claims = TOKENS.claims();
        claims.put("sub", id.toString());
        claims.put("scope", "ADMINISTRATOR");
        claims.put("scp", List.of("ROLE_ADMINISTRATOR"));
        claims.put("roles", List.of("ADMINISTRATOR"));
        claims.put("authorities", List.of("ROLE_ADMINISTRATOR"));
        return TOKENS.token(claims);
    }

    private HttpResponse<String> call(String path, String token) throws Exception {
        return call(path, token, "application/json");
    }

    private HttpResponse<String> call(String path, String token, String accept) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET();
        if (accept != null) {
            request.header(HttpHeaders.ACCEPT, accept);
        }
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        @Primary
        Clock fixedJwtClock() {
            return JwtTestSupport.CLOCK;
        }

        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @TestComponent
    @RestController
    static class ProbeController {

        private final AtomicInteger invocations = new AtomicInteger();

        @GetMapping(ME)
        public String me(Authentication authentication) {
            invocations.incrementAndGet();
            return authentication.getName() + "|" + authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).collect(Collectors.joining(","));
        }
    }
}
