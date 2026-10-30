package vn.edu.hcmut.co3103.lms.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(SecurityConfigIntegrationTest.TestSecurityConfiguration.class)
class SecurityConfigIntegrationTest {

    private static final String ME = "/test/security/me";
    private static final String ADMIN = "/test/security/admin";
    private static final String STUDENT = "/test/security/student";
    private static final String TUTOR = "/test/security/tutor";
    private static final JwtTestSupport TOKENS = startTokens();

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LmsUserAccessRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    static Stream<Arguments> allowedCorsOriginsAndMethods() {
        return Stream.of("http://localhost:3000", "http://localhost:5173")
                .flatMap(origin -> Stream.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                        .map(method -> Arguments.of(origin, method)));
    }

    @ParameterizedTest
    @MethodSource("allowedCorsOriginsAndMethods")
    void permitsCorsPreflightWithoutAuthentication(String origin, String method) throws Exception {
        String headers = "authorization,content-type,accept,origin,x-requested-with,"
                + "access-control-request-method,access-control-request-headers";

        mockMvc.perform(options(ADMIN)
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, headers))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
                .andExpect(result -> {
                    var response = result.getResponse();
                    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS).split(",\\s*"))
                            .containsExactlyInAnyOrder("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
                    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS).split(",\\s*"))
                            .containsExactlyInAnyOrder(headers.split(","));
                    assertThat(response.getHeaders(HttpHeaders.VARY))
                            .contains(HttpHeaders.ORIGIN, HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,
                                    HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS);
                })
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                .andExpect(content().string(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:3000", "http://localhost:5173"})
    void handlesCorsPreflightBeforeBearerAuthentication(String origin) throws Exception {
        mockMvc.perform(options(ADMIN)
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://untrusted.example", "http://localhost:3000.untrusted.example",
            "http://localhost:3001", "https://localhost:3000", "null"})
    void rejectsCorsPreflightFromUntrustedOrigins(String origin) throws Exception {
        mockMvc.perform(options(ADMIN)
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void rejectsCorsPreflightWithUnlistedMethod() throws Exception {
        mockMvc.perform(options(ADMIN)
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "HEAD"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void rejectsCorsPreflightWithUnlistedHeader() throws Exception {
        mockMvc.perform(options(ADMIN)
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Untrusted-Header"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:3000", "http://localhost:5173"})
    void retainsAuthenticationAndLocalAuthorizationForCorsRequests(String origin) throws Exception {
        mockMvc.perform(get(ME).header(HttpHeaders.ORIGIN, origin))
                .andExpect(unauthorizedJson())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));

        LmsUserAccess user = saveUser(LmsRole.STUDENT, AccessStatus.ACTIVE);
        String bearer = "Bearer " + token(user.getUserId().toString());
        mockMvc.perform(get(ME).header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(content().string(user.getUserId() + "|ROLE_STUDENT"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
        mockMvc.perform(get(ADMIN).header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(forbiddenJson(ADMIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void rejectsActualCorsRequestFromUntrustedOrigin() throws Exception {
        mockMvc.perform(get(ME).header(HttpHeaders.ORIGIN, "https://untrusted.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void optionsWithoutPreflightHeadersStillRequiresAuthentication() throws Exception {
        mockMvc.perform(options(ME))
                .andExpect(unauthorizedJson())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @ParameterizedTest
    @EnumSource(LmsRole.class)
    void grantsOnlyTheActiveLocalRole(LmsRole role) throws Exception {
        LmsUserAccess user = saveUser(role, AccessStatus.ACTIVE);

        mockMvc.perform(get(ME).header("Authorization", "Bearer " + token(user.getUserId().toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(user.getUserId() + "|ROLE_" + role.name()));
    }

    @Test
    void returnsForbiddenForUnknownLocalUser() throws Exception {
        mockMvc.perform(get(ME).queryParam("private", "do-not-echo")
                        .header("Authorization", "Bearer " + token(UUID.randomUUID().toString())))
                .andExpect(forbiddenJson(ME));
    }

    @ParameterizedTest
    @EnumSource(LmsRole.class)
    void returnsForbiddenForSuspendedLocalUser(LmsRole role) throws Exception {
        LmsUserAccess user = saveUser(role, AccessStatus.SUSPENDED);

        mockMvc.perform(get(ME).header("Authorization", "Bearer " + token(user.getUserId().toString())))
                .andExpect(forbiddenJson(ME));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", " 550e8400-e29b-41d4-a716-446655440000"})
    void returnsUnauthorizedForInvalidSubject(String subject) throws Exception {
        mockMvc.perform(get(ME).header("Authorization", "Bearer " + token(subject)))
                .andExpect(unauthorizedJson());
    }

    @ParameterizedTest
    @EnumSource(LmsRole.class)
    void enforcesMethodSecurityUsingOnlyTheLocalRole(LmsRole role) throws Exception {
        LmsUserAccess user = saveUser(role, AccessStatus.ACTIVE);

        for (var endpoint : Map.of(LmsRole.STUDENT, STUDENT, LmsRole.TUTOR, TUTOR,
                LmsRole.ADMINISTRATOR, ADMIN).entrySet()) {
            mockMvc.perform(get(endpoint.getValue()).queryParam("private", "do-not-echo")
                            .header("Authorization", "Bearer " + token(user.getUserId().toString())))
                    .andExpect(role == endpoint.getKey() ? status().isOk() : forbiddenJson(endpoint.getValue()));
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rechecksLocalRoleAndStatusForEachRequestWithTheSameToken() throws Exception {
        LmsUserAccess user = saveUser(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);
        try {
            String bearer = "Bearer " + token(user.getUserId().toString());
            mockMvc.perform(get(ADMIN).header("Authorization", bearer)).andExpect(status().isOk());

            user.setLmsRole(LmsRole.STUDENT);
            repository.saveAndFlush(user);
            mockMvc.perform(get(ADMIN).header("Authorization", bearer)).andExpect(forbiddenJson(ADMIN));
            mockMvc.perform(get(ME).header("Authorization", bearer)).andExpect(status().isOk())
                    .andExpect(content().string(user.getUserId() + "|ROLE_STUDENT"));

            user.setAccessStatus(AccessStatus.SUSPENDED);
            repository.saveAndFlush(user);
            mockMvc.perform(get(ME).header("Authorization", bearer)).andExpect(forbiddenJson(ME));

            user.setAccessStatus(AccessStatus.ACTIVE);
            repository.saveAndFlush(user);
            mockMvc.perform(get(ME).header("Authorization", bearer)).andExpect(status().isOk());

            repository.deleteById(user.getUserId());
            mockMvc.perform(get(ME).header("Authorization", bearer)).andExpect(forbiddenJson(ME));
        } finally {
            repository.deleteById(user.getUserId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html", "*/*"})
    void returnsForbiddenJsonRegardlessOfAcceptHeader(String accept) throws Exception {
        LmsUserAccess user = saveUser(LmsRole.STUDENT, AccessStatus.SUSPENDED);
        mockMvc.perform(get(ME).accept(accept).queryParam("private", "do-not-echo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(user.getUserId().toString())))
                .andExpect(forbiddenJson(ME));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html", "*/*"})
    void returnsUnauthorizedWithoutBearerToken(String accept) throws Exception {
        mockMvc.perform(get(ME).accept(accept).queryParam("private", "do-not-echo"))
                .andExpect(unauthorizedJson())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
    }

    @Test
    void returnsUnauthorizedForExpiredToken() throws Exception {
        LmsUserAccess user = saveUser(LmsRole.STUDENT, AccessStatus.ACTIVE);
        String expired = token(user.getUserId().toString(), JwtTestSupport.NOW.minusSeconds(120));

        mockMvc.perform(get(ME).header("Authorization", "Bearer " + expired))
                .andExpect(unauthorizedJson());
    }

    @Test
    void returnsUnauthorizedForInvalidSignature() throws Exception {
        LmsUserAccess user = saveUser(LmsRole.STUDENT, AccessStatus.ACTIVE);
        String valid = token(user.getUserId().toString());
        int signatureStart = valid.lastIndexOf('.') + 1;
        char replacement = valid.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = valid.substring(0, signatureStart) + replacement + valid.substring(signatureStart + 1);

        mockMvc.perform(get(ME).header("Authorization", "Bearer " + tampered))
                .andExpect(unauthorizedJson());
    }

    @Test
    void authenticatesValidRs256Identity() throws Exception {
        LmsUserAccess user = saveUser(LmsRole.STUDENT, AccessStatus.ACTIVE);
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token(user.getUserId().toString())))
                .andExpect(status().isOk())
                .andExpect(content().string(startsWith(user.getUserId() + "|")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"exp", "iat"})
    void returnsUnauthorizedForMissingTimestamp(String name) throws Exception {
        Map<String, Object> claims = TOKENS.claims();
        claims.remove(name);
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.token(claims)))
                .andExpect(unauthorizedJson());
    }

    @ParameterizedTest
    @ValueSource(strings = {"exp", "iat", "sub"})
    void returnsUnauthorizedForMalformedClaim(String name) throws Exception {
        Map<String, Object> claims = TOKENS.claims();
        claims.put(name, List.of("invalid"));
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.token(claims)))
                .andExpect(unauthorizedJson());
    }

    @ParameterizedTest
    @MethodSource("vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport#invalidNumericDates")
    void returnsUnauthorizedForNumericDateOverflowAndInvalidRanges(String name, Object value) throws Exception {
        Map<String, Object> claims = TOKENS.claims();
        claims.put(name, value);

        mockMvc.perform(get(ME).queryParam("private", "do-not-echo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.token(claims)))
                .andExpect(unauthorizedJson())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""));
    }

    @ParameterizedTest
    @MethodSource("vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport#validPreciseNumericDates")
    void authenticatesWithPreciseNumericDatesWithinRange(String name, Object value) throws Exception {
        Map<String, Object> claims = TOKENS.claims();
        claims.put("sub", saveUser(LmsRole.STUDENT, AccessStatus.ACTIVE).getUserId().toString());
        claims.put(name, value);

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.token(claims)))
                .andExpect(status().isOk())
                .andExpect(content().string(claims.get("sub") + "|ROLE_STUDENT"));
    }

    @ParameterizedTest
    @MethodSource("vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport#fractionalTimestampBoundaries")
    void enforcesExactTimestampToleranceOverHttp(String name, String offset, boolean accepted) throws Exception {
        Map<String, Object> claims = TOKENS.claims();
        claims.put("sub", saveUser(LmsRole.STUDENT, AccessStatus.ACTIVE).getUserId().toString());
        claims.put(name, JwtTestSupport.numericDate(JwtTestSupport.NOW).add(new BigDecimal(offset)));

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.token(claims)))
                .andExpect(accepted ? status().isOk() : unauthorizedJson());
    }

    @ParameterizedTest
    @MethodSource("vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport#fractionalLifespans")
    void validatesSubSecondLifespansOverHttp(String issuedFraction, String expiryFraction, boolean accepted)
            throws Exception {
        Map<String, Object> claims = TOKENS.claims();
        BigDecimal second = BigDecimal.valueOf(JwtTestSupport.NOW.getEpochSecond());
        claims.put("sub", saveUser(LmsRole.STUDENT, AccessStatus.ACTIVE).getUserId().toString());
        claims.put("iat", second.add(new BigDecimal(issuedFraction)));
        claims.put("exp", second.add(new BigDecimal(expiryFraction)));

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.token(claims)))
                .andExpect(accepted ? status().isOk() : unauthorizedJson());
    }

    @ParameterizedTest
    @MethodSource("vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport#malformedOptionalClaims")
    void returnsUnauthorizedForMalformedOptionalClaims(String name, Object value) throws Exception {
        Map<String, Object> claims = TOKENS.claims();
        claims.put(name, value);

        mockMvc.perform(get(ME).queryParam("private", "do-not-echo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.token(claims)))
                .andExpect(unauthorizedJson())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""));
    }

    @ParameterizedTest
    @MethodSource("vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport#validOptionalClaims")
    void authenticatesWithWellFormedOptionalClaims(String name, Object value) throws Exception {
        Map<String, Object> claims = TOKENS.claims();
        claims.put("sub", saveUser(LmsRole.STUDENT, AccessStatus.ACTIVE).getUserId().toString());
        claims.put(name, value);

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.token(claims)))
                .andExpect(status().isOk())
                .andExpect(content().string(claims.get("sub") + "|ROLE_STUDENT"));
    }

    @Test
    void rejectsCertificateBoundTokenWithoutClientCertificate() throws Exception {
        Map<String, Object> claims = TOKENS.claims();
        claims.put("sub", saveUser(LmsRole.STUDENT, AccessStatus.ACTIVE).getUserId().toString());
        claims.put("cnf", Map.of("x5t#S256", "test-certificate-thumbprint"));

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.token(claims)))
                .andExpect(unauthorizedJson())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "HS256", "RS512"})
    void returnsUnauthorizedForUnsupportedAlgorithm(String algorithm) throws Exception {
        String token = algorithm.equals("none") ? TOKENS.unsignedToken()
                : TOKENS.token(TOKENS.claims(), JWSAlgorithm.parse(algorithm));
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(unauthorizedJson());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer not-a-valid-jwt", "Bearer ", "Bearer invalid token"})
    void returnsUnauthorizedForMalformedBearerHeader(String authorization) throws Exception {
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(unauthorizedJson());
    }

    @Test
    void returnsUnauthorizedForWrongIssuer() throws Exception {
        Map<String, Object> claims = TOKENS.claims();
        claims.put("iss", "https://untrusted.example.test");
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKENS.token(claims)))
                .andExpect(unauthorizedJson());
    }

    private ResultMatcher forbiddenJson(String path) {
        return result -> {
            status().isForbidden().match(result);
            content().contentType("application/json;charset=UTF-8").match(result);
            header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE).match(result);
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.size()).isEqualTo(5);
            assertThat(body.get("status").asInt()).isEqualTo(403);
            assertThat(body.get("error").asString()).isEqualTo("Forbidden");
            assertThat(body.get("message").asString()).isEqualTo("Access to this resource is denied.");
            assertThat(body.get("path").asString()).isEqualTo(path);
            assertThat(body.get("timestamp").asString()).endsWith("Z");
            assertThat(Instant.parse(body.get("timestamp").asString()))
                    .isBetween(Instant.now().minusSeconds(60), Instant.now());
        };
    }

    private ResultMatcher unauthorizedJson() {
        return result -> {
            status().isUnauthorized().match(result);
            content().contentType("application/json;charset=UTF-8").match(result);
            header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")).match(result);
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.size()).isEqualTo(5);
            assertThat(body.get("status").asInt()).isEqualTo(401);
            assertThat(body.get("error").asString()).isEqualTo("Unauthorized");
            assertThat(body.get("message").asString()).isIn("A valid Bearer access token is required.",
                    "The Bearer access token is invalid or expired.");
            assertThat(body.get("path").asString()).isEqualTo(ME);
            assertThat(body.get("timestamp").asString()).endsWith("Z");
            assertThat(Instant.parse(body.get("timestamp").asString()))
                    .isBetween(Instant.now().minusSeconds(60), Instant.now());
        };
    }

    private LmsUserAccess saveUser(LmsRole role, AccessStatus status) {
        UUID userId = UUID.randomUUID();
        return repository.saveAndFlush(new LmsUserAccess(userId, "Test User", userId + "@example.com",
                role, status, null, null, null));
    }

    private String token(String subject) throws JOSEException {
        return token(subject, JwtTestSupport.NOW.plusSeconds(300));
    }

    private String token(String subject, Instant expiresAt) throws JOSEException {
        Map<String, Object> claims = TOKENS.claims();
        claims.put("iat", expiresAt.minusSeconds(300).getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        // Central Identity claims must never override LMS-local authorities.
        claims.put("scope", "ADMINISTRATOR");
        claims.put("scp", List.of("ROLE_ADMINISTRATOR"));
        claims.put("roles", List.of("ADMINISTRATOR"));
        claims.put("authorities", List.of("ROLE_ADMINISTRATOR"));
        if (subject == null) {
            claims.remove("sub");
        } else {
            claims.put("sub", subject);
        }
        return TOKENS.token(claims);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestSecurityConfiguration {

        @Bean
        @Primary
        Clock fixedJwtClock() {
            return JwtTestSupport.CLOCK;
        }

        @Bean
        SecurityProbeController securityProbeController() {
            return new SecurityProbeController();
        }
    }

    @TestComponent
    @RestController
    static class SecurityProbeController {

        @GetMapping(ME)
        public String me(Authentication authentication) {
            return authentication.getName() + "|" + authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority).collect(Collectors.joining(","));
        }

        @GetMapping(ADMIN)
        @PreAuthorize("hasRole('ADMINISTRATOR')")
        public String admin() {
            return "admin";
        }

        @GetMapping(STUDENT)
        @PreAuthorize("hasRole('STUDENT')")
        public String student() {
            return "student";
        }

        @GetMapping(TUTOR)
        @PreAuthorize("hasRole('TUTOR')")
        public String tutor() {
            return "tutor";
        }
    }
}
