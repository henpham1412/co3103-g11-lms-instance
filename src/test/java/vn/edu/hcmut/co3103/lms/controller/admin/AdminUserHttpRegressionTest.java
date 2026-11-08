package vn.edu.hcmut.co3103.lms.controller.admin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.repository.AuditLogRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;

import java.net.URI;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

// No test-owned transaction: Tomcat completes each request, then assertions read committed state.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "logging.file.name=target/pii-http-regression.log",
        "spring.flyway.schemas=admin_user_http_test",
        "spring.jpa.properties.hibernate.default_schema=admin_user_http_test"
})
@Import(AdminUserHttpRegressionTest.JwtClockConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AdminUserHttpRegressionTest {
    private static final String USERS = "/api/v1/admin/users";
    private static final Instant ORIGINAL_TIME = Instant.parse("2025-01-01T00:00:00Z");
    private static final JwtTestSupport TOKENS = startTokens();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Value("${local.server.port}") private int port;
    @Autowired private LmsUserAccessRepository users;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @MockitoSpyBean(name = "fixedJwtClock") private Clock clock;
    private LmsUserAccess admin;
    private LmsUserAccess target;

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
    static void stopServers() {
        CLIENT.close();
        TOKENS.close();
    }

    @BeforeEach
    void createFixtures() {
        // Existing admin requests reuse a login; their mutation snapshots exclude login bookkeeping.
        UUID adminId = UUID.randomUUID();
        admin = users.saveAndFlush(new LmsUserAccess(adminId, "Administrator", adminId + "@example.test",
                LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE, JwtTestSupport.NOW, ORIGINAL_TIME, ORIGINAL_TIME,
                null, Instant.ofEpochSecond(JwtTestSupport.NOW.getEpochSecond() - 300)));
        target = saveUser("Nguyễn An", LmsRole.STUDENT, AccessStatus.ACTIVE);
    }

    @AfterEach
    void cleanFixtures() {
        auditLogs.deleteAllInBatch();
        users.deleteAllInBatch();
    }

    @ParameterizedTest
    @EnumSource(LmsRole.class)
    void trustedIdentitySynchronizesDisplayAndLocalLoginWithoutChangingAccess(LmsRole role) throws Exception {
        target.setLmsRole(role);
        users.saveAndFlush(target);
        var claims = TOKENS.claims();
        claims.put("sub", target.getUserId().toString());
        claims.put("userId", admin.getUserId().toString()); // Only the verified sub binds local identity.
        claims.put("fullName", "Updated CI Name");
        claims.put("email", admin.getEmail()); // Display emails are deliberately not unique.
        claims.put("roles", List.of("ADMINISTRATOR"));
        claims.put("access_status", "SUSPENDED");
        String bearer = TOKENS.token(claims);

        var response = sendToken("GET", USERS, null, bearer);
        if (role == LmsRole.ADMINISTRATOR) {
            json(response, 200);
        } else {
            error(response, 403, "FORBIDDEN");
        }
        var stored = users.findById(target.getUserId()).orElseThrow();
        assertThat(stored.getFullName()).isEqualTo("Updated CI Name");
        assertThat(stored.getEmail()).isEqualTo(admin.getEmail());
        assertThat(stored.getLmsRole()).isEqualTo(role);
        assertThat(stored.getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(stored.getUserId()).isEqualTo(target.getUserId());
        assertThat(stored.getCreatedAt()).isEqualTo(ORIGINAL_TIME);
        assertThat(stored.getLastLoginAt()).isEqualTo(JwtTestSupport.NOW);
        assertThat(stored.getUpdatedAt()).isEqualTo(JwtTestSupport.NOW);
        assertThat(users.findById(admin.getUserId()).orElseThrow()).usingRecursiveComparison().isEqualTo(admin);
        assertThat(auditLogs.count()).isZero();

        sendToken("GET", USERS, null, bearer);
        assertThat(users.findById(target.getUserId()).orElseThrow()).usingRecursiveComparison().isEqualTo(stored);
        claims.put("iat", JwtTestSupport.NOW.getEpochSecond() + 1);
        claims.put("fullName", "Newer CI Name");
        sendToken("GET", USERS, null, TOKENS.token(claims));
        assertThat(users.findById(target.getUserId()).orElseThrow().getFullName()).isEqualTo("Newer CI Name");
    }

    @Test
    void olderTrustedTokenCannotOverwriteNewerIdentityProjection() throws Exception {
        var claims = TOKENS.claims();
        claims.put("sub", admin.getUserId().toString());
        claims.put("iat", JwtTestSupport.numericDate(JwtTestSupport.NOW));
        claims.put("fullName", "Newer CI Name");
        claims.put("email", "newer-ci@example.test");
        json(sendToken("GET", USERS, null, TOKENS.token(claims)), 200);
        var newer = users.findById(admin.getUserId()).orElseThrow();
        assertThat(newer.getFullName()).isEqualTo("Newer CI Name");
        assertThat(newer.getEmail()).isEqualTo("newer-ci@example.test");
        assertThat(newer.getUserId()).isEqualTo(admin.getUserId());
        assertThat(newer.getLmsRole()).isEqualTo(admin.getLmsRole());
        assertThat(newer.getAccessStatus()).isEqualTo(admin.getAccessStatus());
        assertThat(newer.getCreatedAt()).isEqualTo(admin.getCreatedAt());

        doReturn(JwtTestSupport.NOW.plusSeconds(1)).when(clock).instant();
        claims.put("iat", JwtTestSupport.numericDate(JwtTestSupport.NOW.minusSeconds(30)));
        claims.put("fullName", "Older CI Name");
        claims.put("email", "older-ci@example.test");
        json(sendToken("GET", USERS, null, TOKENS.token(claims)), 200);
        assertThat(users.findById(admin.getUserId()).orElseThrow()).usingRecursiveComparison().isEqualTo(newer);
        assertThat(auditLogs.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.000000001", "0.000000999"})
    void futureIssuedTokenReuseDoesNotRecordAnotherLocalLogin(String fraction) throws Exception {
        var claims = TOKENS.claims();
        claims.put("sub", admin.getUserId().toString());
        claims.put("iat", JwtTestSupport.numericDate(JwtTestSupport.NOW.plusSeconds(45))
                .add(new java.math.BigDecimal(fraction)));
        claims.put("fullName", "Future CI Name");
        claims.put("email", "future-ci@example.test");
        String bearer = TOKENS.token(claims);
        json(sendToken("GET", USERS, null, bearer), 200);
        var first = users.findById(admin.getUserId()).orElseThrow();
        assertThat(first.getLastLoginAt()).isEqualTo(JwtTestSupport.NOW);
        assertThat(first.getUpdatedAt()).isEqualTo(JwtTestSupport.NOW);

        for (int seconds : List.of(1, 20, 46)) {
            doReturn(JwtTestSupport.NOW.plusSeconds(seconds)).when(clock).instant();
            json(sendToken("GET", USERS, null, bearer), 200);
            assertThat(users.findById(admin.getUserId()).orElseThrow()).usingRecursiveComparison().isEqualTo(first);
        }
        assertThat(auditLogs.count()).isZero();
    }

    @Test
    void suspendedIdentityCanRefreshDisplayButCannotLoginOrReactivateAccess() throws Exception {
        target.setAccessStatus(AccessStatus.SUSPENDED);
        users.saveAndFlush(target);
        var claims = TOKENS.claims();
        claims.put("sub", target.getUserId().toString());
        claims.put("fullName", "Suspended CI Name");
        claims.put("email", "suspended-ci@example.test");
        claims.put("lms_role", "ADMINISTRATOR");
        claims.put("access_status", "ACTIVE");
        error(sendToken("GET", USERS, null, TOKENS.token(claims)), 403, "FORBIDDEN");
        var stored = users.findById(target.getUserId()).orElseThrow();
        assertThat(stored.getFullName()).isEqualTo("Suspended CI Name");
        assertThat(stored.getEmail()).isEqualTo("suspended-ci@example.test");
        assertThat(stored.getLmsRole()).isEqualTo(LmsRole.STUDENT);
        assertThat(stored.getAccessStatus()).isEqualTo(AccessStatus.SUSPENDED);
        assertThat(stored.getLastLoginAt()).isEqualTo(ORIGINAL_TIME);
    }

    @Test
    void absentAndPartialDisplayClaimsPreserveOtherLocalFieldsAndNeverProvisionUsers() throws Exception {
        var claims = TOKENS.claims();
        claims.put("sub", target.getUserId().toString());
        error(sendToken("GET", USERS, null, TOKENS.token(claims)), 403, "FORBIDDEN");
        var loggedIn = users.findById(target.getUserId()).orElseThrow();
        assertThat(loggedIn).usingRecursiveComparison().ignoringFields("lastLoginAt", "lastLoginTokenIat", "updatedAt")
                .isEqualTo(target);
        assertThat(loggedIn.getLastLoginAt()).isEqualTo(JwtTestSupport.NOW);
        claims.put("email", "partial-ci@example.test");
        error(sendToken("GET", USERS, null, TOKENS.token(claims)), 403, "FORBIDDEN");
        var partial = users.findById(target.getUserId()).orElseThrow();
        assertThat(partial.getEmail()).isEqualTo("partial-ci@example.test");
        assertThat(partial.getFullName()).isEqualTo(target.getFullName());

        claims.put("sub", UUID.randomUUID().toString());
        claims.put("email", admin.getEmail());
        claims.put("fullName", "Unprovisioned CI Name");
        error(sendToken("GET", USERS, null, TOKENS.token(claims)), 403, "FORBIDDEN");
        assertThat(users.count()).isEqualTo(2);
        assertThat(users.findById(admin.getUserId()).orElseThrow()).usingRecursiveComparison().isEqualTo(admin);
    }

    static Stream<Arguments> invalidDisplayClaims() {
        return Stream.of(Arguments.of("fullName", null), Arguments.of("fullName", List.of("Name")),
                Arguments.of("fullName", " "), Arguments.of("fullName", "n".repeat(151)),
                Arguments.of("email", true), Arguments.of("email", "not-an-email"),
                Arguments.of("email", "n".repeat(244) + "@example.test"));
    }

    @ParameterizedTest
    @MethodSource("invalidDisplayClaims")
    @MethodSource("vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport#unpreservableDisplayClaims")
    void malformedDisplayClaimCannotPartiallySynchronizeIdentity(String name, Object value) throws Exception {
        var claims = TOKENS.claims();
        claims.put("sub", target.getUserId().toString());
        claims.put("fullName", "Must Not Persist");
        claims.put("email", "must-not-persist@example.test");
        claims.put(name, value);
        // Preserve lone surrogates as JSON escapes instead of replacing them during UTF-8 encoding.
        String claimsJson = mapper.writer().with(JsonWriteFeature.ESCAPE_NON_ASCII).writeValueAsString(claims);
        var response = sendToken("GET", USERS, null, TOKENS.token(claimsJson));
        error(response, 401, "UNAUTHORIZED");
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertUnchanged();
    }

    @Test
    void validUnicodeDisplayClaimsArePreservedInDatabase() throws Exception {
        String fullName = "Nguyễn \uD83D\uDE00 \uD840\uDC00";
        String email = "nguyễn@example.test";
        var claims = TOKENS.claims();
        claims.put("sub", admin.getUserId().toString());
        claims.put("fullName", fullName);
        claims.put("email", email);

        json(sendToken("GET", USERS, null, TOKENS.token(claims)), 200);

        var stored = users.findById(admin.getUserId()).orElseThrow();
        assertThat(stored.getFullName()).isEqualTo(fullName);
        assertThat(stored.getEmail()).isEqualTo(email);
        assertThat(stored).usingRecursiveComparison()
                .ignoringFields("fullName", "email", "lastSyncedProfileIat", "updatedAt").isEqualTo(admin);
        assertThat(auditLogs.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"signature", "issuer", "precision"})
    void untrustedIdentityNeverSynchronizesLocalDisplay(String failure) throws Exception {
        var claims = TOKENS.claims();
        claims.put("sub", target.getUserId().toString());
        claims.put("fullName", "Untrusted CI Name");
        claims.put("email", "untrusted-ci@example.test");
        if (failure.equals("issuer")) {
            claims.put("iss", "https://untrusted.example.test");
        } else if (failure.equals("precision")) {
            claims.put("iat", JwtTestSupport.numericDate(JwtTestSupport.NOW)
                    .add(new java.math.BigDecimal("60.000000000001")));
        }
        String token = TOKENS.token(claims);
        if (failure.equals("signature")) {
            int start = token.lastIndexOf('.') + 1;
            token = token.substring(0, start) + (token.charAt(start) == 'A' ? 'B' : 'A') + token.substring(start + 1);
        }
        var response = sendToken("GET", USERS, null, token);
        error(response, 401, "UNAUTHORIZED");
        assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer");
        assertUnchanged();
    }

    @Test
    void failedProfileSynchronizationReturnsSafe500AndRollsBack(CapturedOutput output) throws Exception {
        jdbc.execute("ALTER TABLE admin_user_http_test.lms_user_access ADD CONSTRAINT reject_test_profile CHECK (full_name <> 'SYNC_DATABASE_NAME_MARKER')");
        try {
            var claims = TOKENS.claims();
            claims.put("sub", target.getUserId().toString());
            claims.put("fullName", "SYNC_DATABASE_NAME_MARKER");
            claims.put("email", "sync-database-pii@example.test");
            var response = sendToken("GET", USERS, null, TOKENS.token(claims));
            error(response, 500, "INTERNAL_SERVER_ERROR");
            assertThat(mapper.readTree(response.body())).isEqualTo(mapper.readTree("""
                    {"code":"INTERNAL_SERVER_ERROR","message":"An unexpected server error occurred.","details":[]}
                    """));
            assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
            assertUnchanged();
            assertNoLogPii(output, "SYNC_DATABASE_NAME_MARKER", "sync-database-pii@example.test", "Failing row contains");
        } finally {
            jdbc.execute("ALTER TABLE admin_user_http_test.lms_user_access DROP CONSTRAINT reject_test_profile");
        }
    }

    @Test
    void concurrentRevocationSurvivesQueuedProfileSynchronization() throws Exception {
        target.setLmsRole(LmsRole.ADMINISTRATOR);
        users.saveAndFlush(target);
        var claims = TOKENS.claims();
        claims.put("sub", target.getUserId().toString());
        claims.put("fullName", "Concurrent CI Name");
        claims.put("email", "concurrent-ci@example.test");
        String token = TOKENS.token(claims);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<HttpResponse<String>> pending;
            try (var barrier = dataSource.getConnection()) {
                barrier.setAutoCommit(false);
                try (var statement = barrier.prepareStatement(
                        "SELECT user_id FROM admin_user_http_test.lms_user_access WHERE user_id = ? FOR UPDATE")) {
                    statement.setObject(1, target.getUserId());
                    statement.execute();
                }
                pending = executor.submit(() -> sendToken("GET", USERS, null, token));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean blocked = false;
                while (System.nanoTime() < deadline) {
                    blocked = jdbc.queryForObject("""
                            SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                            WHERE datname = current_database() AND pid <> pg_backend_pid()
                              AND wait_event_type = 'Lock'
                              AND query ILIKE '%update%admin_user_http_test.lms_user_access%')
                            """, Boolean.class);
                    if (blocked) break;
                    Thread.sleep(20);
                }
                assertThat(blocked).as("authentication waits after reading the old active role").isTrue();
                try (var statement = barrier.prepareStatement("""
                        UPDATE admin_user_http_test.lms_user_access
                        SET lms_role = 'STUDENT', access_status = 'SUSPENDED' WHERE user_id = ?
                        """)) {
                    statement.setObject(1, target.getUserId());
                    statement.executeUpdate();
                }
                barrier.commit();
            }
            error(pending.get(15, TimeUnit.SECONDS), 403, "FORBIDDEN");
        }
        var stored = users.findById(target.getUserId()).orElseThrow();
        assertThat(stored.getFullName()).isEqualTo("Concurrent CI Name");
        assertThat(stored.getEmail()).isEqualTo("concurrent-ci@example.test");
        assertThat(stored.getLmsRole()).isEqualTo(LmsRole.STUDENT);
        assertThat(stored.getAccessStatus()).isEqualTo(AccessStatus.SUSPENDED);
        assertThat(stored.getLastLoginAt()).isEqualTo(ORIGINAL_TIME);
    }

    private HttpResponse<String> sendToken(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @ParameterizedTest
    @ValueSource(strings = {USERS, "/api/admin/users"})
    void officialUserAdministrationContractRoundTrips(String route) throws Exception {
        var first = json(send("GET", route + "?page=1&size=1&sort=created_at,desc&q=" + target.getUserId(),
                null, "application/json", admin.getUserId()), 200);
        assertThat(first.size()).isEqualTo(6);
        assertThat(first.path("page").asInt()).isEqualTo(1);
        assertThat(first.path("size").asInt()).isEqualTo(1);
        assertThat(first.path("total_items").asInt()).isEqualTo(1);
        assertThat(first.path("total_pages").asInt()).isEqualTo(1);
        assertThat(first.path("last").asBoolean()).isTrue();
        assertProjection(first.path("items").get(0), "STUDENT", "ACTIVE");
        assertThat(first.has("content")).isFalse();

        var changed = json(send("PATCH", route + "/" + target.getUserId(),
                "{\"lms_role\":\"TUTOR\",\"access_status\":\"SUSPENDED\"}",
                "application/json;charset=UTF-8", admin.getUserId()), 200);
        assertProjection(changed, "TUTOR", "SUSPENDED");
        var stored = users.findById(target.getUserId()).orElseThrow();
        assertThat(stored.getLmsRole()).isEqualTo(LmsRole.TUTOR);
        assertThat(stored.getAccessStatus()).isEqualTo(AccessStatus.SUSPENDED);
        assertThat(stored.getUpdatedAt()).isAfter(ORIGINAL_TIME);
        assertProjection(json(send("GET", route + "/" + target.getUserId(), null,
                "application/json", admin.getUserId()), 200), "TUTOR", "SUSPENDED");

        var filtered = json(send("GET", route + "?lms_role=TUTOR&access_status=SUSPENDED&sort=full_name,asc",
                null, "application/json", admin.getUserId()), 200);
        assertThat(filtered.path("items").size()).isEqualTo(1);
        assertProjection(filtered.path("items").get(0), "TUTOR", "SUSPENDED");
        assertThat(auditLogs.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "&sort=created_at,desc", "&sort=created_at,asc",
            "&sort=lms_role,asc", "&sort=lms_role,desc",
            "&sort=access_status,asc", "&sort=access_status,desc",
            "&sort=full_name,asc", "&sort=email,desc", "&sort=updated_at,desc",
            "&sort=last_login_at,asc", "&sort=last_login_at,desc",
            "&sort=created_at,desc&sort=user_id,asc",
            "&sort=created_at,desc&sort=user_id,desc",
            "&sort=user_id,desc&sort=full_name,asc",
            "&sort=lms_role,asc&sort=userId,desc&sort=access_status,desc"})
    void paginationWithTiedSortValuesEnumeratesEveryUserExactlyOnce(String sort) throws Exception {
        int total = 103;
        int size = 10;
        int totalPages = (total + size - 1) / size;
        var fixtures = new ArrayList<LmsUserAccess>();
        // Insert in a different order from UUID order; every requested non-ID sort value ties.
        for (int i = total; i > 0; i--) {
            fixtures.add(new LmsUserAccess(new UUID(0, i), "Tied Pagination User", "tied@example.test",
                    LmsRole.STUDENT, AccessStatus.ACTIVE, null, ORIGINAL_TIME, ORIGINAL_TIME));
        }
        users.saveAllAndFlush(fixtures);
        var expectedIds = fixtures.stream().map(user -> user.getUserId().toString()).sorted().toList();
        if (sort.contains("user_id,desc") || sort.contains("userId,desc")) {
            expectedIds = expectedIds.reversed();
        }

        for (String route : List.of(USERS, "/api/admin/users")) {
            var seenIds = new ArrayList<String>();
            for (int page = 1; page <= totalPages + 1; page++) {
                var result = json(send("GET", route + "?q=Tied%20Pagination%20User&page=" + page
                        + "&size=" + size + sort, null, "application/json", admin.getUserId()), 200);
                assertThat(result.path("page").asInt()).isEqualTo(page);
                assertThat(result.path("size").asInt()).isEqualTo(size);
                assertThat(result.path("total_items").asInt()).isEqualTo(total);
                assertThat(result.path("total_pages").asInt()).isEqualTo(totalPages);
                assertThat(result.path("last").asBoolean()).isEqualTo(page >= totalPages);
                assertThat(result.path("items").size()).isEqualTo(Math.min(size, Math.max(0, total - (page - 1) * size)));
                for (var user : result.path("items")) {
                    seenIds.add(user.path("user_id").asText());
                }
            }
            assertThat(seenIds).doesNotHaveDuplicates().containsExactlyElementsOf(expectedIds);
        }
    }

    static Stream<Arguments> unacceptableRepresentations() {
        // Exercise negotiation on every endpoint; charset/quality variants need only one HTTP path.
        return Stream.concat(
                Stream.of("GET_LIST", "GET_DETAIL", "PATCH")
                        .map(endpoint -> Arguments.of(endpoint, "text/html")),
                Stream.of("application/json;charset=ISO-8859-1", "application/json;q=0", "*/*;q=0",
                        "application/json;q=0, */*;q=1", "application/*;q=0, */*;q=1",
                        "application/json;charset=UTF-8;q=0, application/json;q=1",
                        "application/problem+json", "application/json;profile=custom")
                        .map(accept -> Arguments.of("PATCH", accept)));
    }

    @ParameterizedTest
    @MethodSource("unacceptableRepresentations")
    void rejectsUnsupportedResponseBeforeCommit(String endpoint, String accept) throws Exception {
        String path = endpoint.equals("GET_LIST") ? USERS : USERS + "/" + target.getUserId();
        String method = endpoint.equals("PATCH") ? "PATCH" : "GET";
        error(send(method, path, method.equals("PATCH") ? "{\"lms_role\":\"TUTOR\"}" : null,
                accept, admin.getUserId()), 406, "NOT_ACCEPTABLE");
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json;charset=UTF-8", "application/json;charset=utf8", "*/*",
            "application/json;charset=ISO-8859-1, application/json;charset=UTF-8;q=0.5",
            "application/json;charset=ISO-8859-1, */*;q=0.1",
            "application/json;q=1, */*;q=0", "application/*;q=0, application/json;q=0.5"})
    void supportedRepresentationIsWrittenAsUtf8AfterCommit(String accept) throws Exception {
        var response = send("PATCH", USERS + "/" + target.getUserId(), "{\"lms_role\":\"TUTOR\"}",
                accept, admin.getUserId());
        assertProjection(json(response, 200), "TUTOR", "ACTIVE");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow().toLowerCase())
                .isEqualTo("application/json;charset=utf-8");
        assertThat(users.findById(target.getUserId()).orElseThrow().getLmsRole()).isEqualTo(LmsRole.TUTOR);
        assertThat(auditLogs.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=0", "page=abc", "page=2147483648", "page=1.5", "page=",
            "size=-1", "size=0", "size=abc", "size=2147483648", "size=1.5", "size=",
            "size=2001", "size=99999", "page=2147483647&size=20", "page=1073741825&size=2",
            "page=1&page=2", "size=1&size=2", "q=one&q=two",
            "sort=unknown,asc", "sort=created_at,banana", "sort=", "sort=,asc",
            "sort=created_at,", "sort=created_at,asc,desc", "sort=full_name,asc&sort=unknown,asc",
            "lms_role=TUTOR&role=TUTOR", "lms_role=TUTOR&role=STUDENT",
            "lms_role=TUTOR&lms_role=STUDENT", "role=TUTOR&role=TUTOR",
            "access_status=ACTIVE&status=ACTIVE", "access_status=ACTIVE&status=SUSPENDED",
            "access_status=ACTIVE&access_status=SUSPENDED", "status=ACTIVE&status=ACTIVE"})
    void adminQueryValidationUsesDoc05Code(String query) throws Exception {
        for (String route : List.of(USERS, "/api/admin/users")) {
            error(send("GET", route + "?" + query, null, "application/json", admin.getUserId()),
                    400, "VALIDATION_ERROR");
        }
        assertUnchanged();
    }

    @Test
    void paginationDefaultsMaximumSizeAndTerminalMetadataAreCorrect() throws Exception {
        var defaults = json(send("GET", USERS, null, "application/json", admin.getUserId()), 200);
        assertThat(defaults.path("page").asInt()).isEqualTo(1);
        assertThat(defaults.path("size").asInt()).isEqualTo(20);
        var maximum = json(send("GET", USERS + "?size=2000", null, "application/json", admin.getUserId()), 200);
        assertThat(maximum.path("size").asInt()).isEqualTo(2000);
        var last = json(send("GET", USERS + "?page=2147483647&size=1", null,
                "application/json", admin.getUserId()), 200);
        assertThat(last.path("page").asLong()).isEqualTo(Integer.MAX_VALUE);
        assertThat(last.path("items").size()).isZero();
        assertThat(last.path("total_items").asInt()).isEqualTo(2);
        assertThat(last.path("total_pages").asInt()).isEqualTo(2);
        assertThat(last.path("last").asBoolean()).isTrue();
    }

    static Stream<Arguments> containerRejectedNulPaths() {
        // Tomcat rejects these paths before routing/authentication; cover each dimension without a product.
        return Stream.of(
                Arguments.of(USERS, false, "GET", "ANONYMOUS", "*/*"),
                Arguments.of(USERS, true, "PATCH", "ADMINISTRATOR", "text/html"),
                Arguments.of(USERS, false, "PATCH", "STUDENT", "*/*"),
                Arguments.of(USERS, true, "GET", "ANONYMOUS", "text/html"),
                Arguments.of("/api/admin/users", false, "PATCH", "ANONYMOUS", "text/html"),
                Arguments.of("/api/admin/users", true, "GET", "ADMINISTRATOR", "*/*"),
                Arguments.of("/api/admin/users", false, "GET", "STUDENT", "text/html"),
                Arguments.of("/api/admin/users", true, "PATCH", "ANONYMOUS", "*/*"));
    }

    @ParameterizedTest
    @MethodSource("containerRejectedNulPaths")
    void containerRejectedNulPathsUseStandardJsonEnvelope(String route, boolean appendToUuid,
                                                         String method, String caller, String accept) throws Exception {
        var usersBefore = jdbc.queryForList("SELECT * FROM admin_user_http_test.lms_user_access ORDER BY user_id");
        var auditsBefore = jdbc.queryForList("SELECT * FROM admin_user_http_test.audit_logs ORDER BY audit_log_id");
        var setupBefore = jdbc.queryForList("SELECT * FROM admin_user_http_test.lms_setup_state");
        UUID callerId = switch (caller) {
            case "ADMINISTRATOR" -> admin.getUserId();
            case "STUDENT" -> target.getUserId();
            default -> null;
        };
        String path = route + "/" + (appendToUuid ? target.getUserId().toString() : "") + "%00";
        var response = send(method, path, method.equals("PATCH") ? "{\"lms_role\":\"TUTOR\"}" : null,
                accept, callerId);

        assertThat(json(response, 400)).isEqualTo(mapper.readTree("""
                {"code":"BAD_REQUEST","message":"Bad Request","details":[]}
                """));
        assertThat(response.body()).doesNotContain("<html", "<!doctype", "Exception", "Tomcat",
                "org.apache", "lms_user_access", target.getUserId().toString());
        assertProjection(json(send("GET", route + "/" + target.getUserId(), null,
                "application/json", admin.getUserId()), 200), "STUDENT", "ACTIVE");
        assertThat(jdbc.queryForList("SELECT * FROM admin_user_http_test.lms_user_access ORDER BY user_id"))
                .isEqualTo(usersBefore);
        assertThat(jdbc.queryForList("SELECT * FROM admin_user_http_test.audit_logs ORDER BY audit_log_id"))
                .isEqualTo(auditsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM admin_user_http_test.lms_setup_state"))
                .isEqualTo(setupBefore);
    }

    static Stream<Arguments> nulSearchQueries() {
        return Stream.of(USERS, "/api/admin/users").flatMap(route -> Stream.of(
                "%00",
                "a%00b&lms_role=STUDENT&access_status=ACTIVE",
                "%20%09%00%0A&role=STUDENT&status=ACTIVE&page=1&size=2000&sort=created_at,desc",
                "%00a%00&lms_role=TUTOR&access_status=SUSPENDED&page=2147483647&size=1")
                .map(query -> Arguments.of(route, query)));
    }

    @ParameterizedTest
    @MethodSource("nulSearchQueries")
    void nulSearchReturnsValidationErrorWithoutMutationOrServerErrorLog(String route, String query) throws Exception {
        var before = jdbc.queryForList("SELECT * FROM admin_user_http_test.lms_user_access ORDER BY user_id");
        long auditsBefore = auditLogs.count();
        Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            var response = send("GET", route + "?q=" + query, null, "application/json", admin.getUserId());
            error(response, 400, "VALIDATION_ERROR");
            assertThat(mapper.readTree(response.body())).isEqualTo(mapper.readTree("""
                    {"code":"VALIDATION_ERROR","message":"Validation failed","details":[]}
                    """));
            var recovered = json(send("GET", route + "?q=Administrator", null,
                    "application/json", admin.getUserId()), 200);
            assertThat(recovered.path("total_items").asInt()).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT * FROM admin_user_http_test.lms_user_access ORDER BY user_id"))
                    .isEqualTo(before);
            assertThat(auditLogs.count()).isEqualTo(auditsBefore);
            assertThat(logs.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR));
            assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains("22021") || message.contains("invalid byte sequence"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {USERS, "/api/admin/users"})
    void literalPercentEncodedTextIsNotDecodedTwice(String route) throws Exception {
        var literal = saveUser("Literal a%00b", LmsRole.STUDENT, AccessStatus.ACTIVE);
        var result = json(send("GET", route + "?q=a%2500b", null, "application/json", admin.getUserId()), 200);
        assertThat(result.path("total_items").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("user_id").asText()).isEqualTo(literal.getUserId().toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"lms_role\":\"OWNER\"}", "{\"access_status\":\"DELETED\"}",
            "{\"lms_role\":2}", "{\"access_status\":1}", "{\"lms_role\":\"2\"}",
            "{\"access_status\":true}", "{\"lms_role\":[]}", "{\"access_status\":{}}",
            "{\"lms_rol\":\"TUTOR\"}", "{\"role\":\"TUTOR\",\"lms_role\":\"STUDENT\"}",
            "{\"access_status\":\"ACTIVE\",\"accessStatus\":\"SUSPENDED\"}",
            "{\"lms_role\":\"TUTOR\",\"full_name\":\"Replaced\"}", "{\"email\":\"other@example.test\"}",
            "{\"user_id\":\"00000000-0000-0000-0000-000000000000\"}",
            "{\"last_login_at\":\"2030-01-01T00:00:00Z\"}"})
    void invalidOrReadOnlyUpdateFieldsAreRejectedWithoutMutation(String body) throws Exception {
        error(send("PATCH", USERS + "/" + target.getUserId(), body, "application/json", admin.getUserId()),
                400, "VALIDATION_ERROR");
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"lms_role=OWNER", "access_status=DELETED", "role=1", "status=1", "lms_role="})
    void invalidEnumFiltersUseDomainValidationCode(String query) throws Exception {
        error(send("GET", USERS + "?" + query, null, "application/json", admin.getUserId()), 400, "VALIDATION_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {USERS, "/api/admin/users"})
    void detailLookupAndMissingUserErrorsMatchContract(String route) throws Exception {
        error(send("GET", route + "/" + UUID.randomUUID(), null, "application/json", admin.getUserId()),
                404, "LOCAL_USER_NOT_FOUND");
        error(send("PATCH", route + "/" + UUID.randomUUID(), "{\"lms_role\":\"TUTOR\"}",
                "application/json", admin.getUserId()), 404, "LOCAL_USER_NOT_FOUND");
        error(send("GET", route + "/not-a-uuid", null, "application/json", admin.getUserId()), 400, "BAD_REQUEST");
        assertUnchanged();
    }

    @ParameterizedTest
    @CsvSource({"STUDENT,ACTIVE", "TUTOR,ACTIVE", "ADMINISTRATOR,SUSPENDED"})
    void detailRequiresAnActiveLocalAdministrator(LmsRole role, AccessStatus status) throws Exception {
        var caller = saveUser("Denied", role, status);
        for (String route : List.of(USERS, "/api/admin/users")) {
            error(send("GET", route + "/" + target.getUserId(), null, "application/json", caller.getUserId()),
                    403, "FORBIDDEN");
        }
        assertUnchanged();
    }

    @Test
    void detailRejectsMissingAuthenticationAndUnknownLocalIdentity() throws Exception {
        for (String route : List.of(USERS, "/api/admin/users")) {
            error(send("GET", route + "/" + target.getUserId(), null, "application/json", null), 401, "UNAUTHORIZED");
            error(send("GET", route + "/" + target.getUserId(), null, "application/json", UUID.randomUUID()),
                    403, "FORBIDDEN");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"lms_role\":\"STUDENT\"}", "{\"access_status\":\"SUSPENDED\"}"})
    void lastAdminTransitionsReturnConflictWithoutAudit(String body) throws Exception {
        error(send("PATCH", USERS + "/" + admin.getUserId(), body, "application/json", admin.getUserId()),
                409, "INVALID_ROLE_OR_STATUS_TRANSITION");
        assertThat(users.findById(admin.getUserId()).orElseThrow()).usingRecursiveComparison().isEqualTo(admin);
        assertUnchanged();
    }

    @ParameterizedTest
    @CsvSource({"STUDENT,ACTIVE,true", "ADMINISTRATOR,SUSPENDED,true",
            "STUDENT,ACTIVE,false", "ADMINISTRATOR,SUSPENDED,false"})
    void queuedRevokedAdministratorCannotRestoreOwnAccessOrMutateAnotherUser(
            LmsRole revokedRole, AccessStatus revokedStatus, boolean self) throws Exception {
        var revoked = saveUser("Queued administrator", LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);
        String revocation = mapper.writeValueAsString(Map.of(
                "lms_role", revokedRole.name(), "access_status", revokedStatus.name()));
        UUID targetId = self ? revoked.getUserId() : target.getUserId();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> first;
            Future<HttpResponse<String>> queued;
            // Closing the connection releases the barrier even if scheduling assertions fail.
            try (var barrier = dataSource.getConnection()) {
                barrier.setAutoCommit(false);
                try (var statement = barrier.createStatement()) {
                    statement.execute("SELECT id FROM admin_user_http_test.lms_setup_state WHERE id = 'GLOBAL' FOR UPDATE");
                }
                first = executor.submit(() -> send("PATCH", USERS + "/" + revoked.getUserId(),
                        revocation, "application/json", admin.getUserId()));
                awaitBlockedUpdates(1);
                queued = executor.submit(() -> send("PATCH", USERS + "/" + targetId,
                        "{\"lms_role\":\"ADMINISTRATOR\",\"access_status\":\"ACTIVE\"}",
                        "application/json", revoked.getUserId()));
                // Both HTTP requests have authenticated against the still-active committed actor.
                awaitBlockedUpdates(2);
                assertThat(first.isDone()).isFalse();
                assertThat(queued.isDone()).isFalse();
                barrier.commit();
            }
            json(first.get(15, TimeUnit.SECONDS), 200);
            error(queued.get(15, TimeUnit.SECONDS), 403, "FORBIDDEN");
        }

        var stored = users.findById(revoked.getUserId()).orElseThrow();
        assertThat(stored.getLmsRole()).isEqualTo(revokedRole);
        assertThat(stored.getAccessStatus()).isEqualTo(revokedStatus);
        assertThat(users.findById(target.getUserId()).orElseThrow()).usingRecursiveComparison().isEqualTo(target);
        error(send("GET", USERS, null, "application/json", revoked.getUserId()), 403, "FORBIDDEN");
        assertThat(auditLogs.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getActorId()).isEqualTo(admin.getUserId());
            assertThat(event.getEntityId()).isEqualTo(revoked.getUserId());
            assertThat(mapper.readTree(event.getDetails()).path("after"))
                    .isEqualTo(mapper.readTree(revocation));
        });
    }

    private void awaitBlockedUpdates(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            int blocked = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                      AND wait_event_type = 'Lock'
                      AND query LIKE '%admin_user_http_test.lms_setup_state%'
                    """, Integer.class);
            if (blocked == expected) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected " + expected + " HTTP updates waiting on the GLOBAL lock");
    }

    @Test
    void accessUpdateAndAuditCommitAtomically() throws Exception {
        Instant before = Instant.now();
        json(send("PATCH", USERS + "/" + target.getUserId(),
                "{\"lms_role\":\"TUTOR\",\"access_status\":\"SUSPENDED\"}", "application/json", admin.getUserId()), 200);
        assertThat(auditLogs.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getAuditLogId()).isNotNull();
            assertThat(event.getActorId()).isEqualTo(admin.getUserId());
            assertThat(event.getEntityId()).isEqualTo(target.getUserId());
            assertThat(event.getEntityType()).isEqualTo("LmsUserAccess");
            assertThat(event.getAction()).isEqualTo("LMS_USER_ACCESS_UPDATED");
            assertThat(event.getCreatedAt()).isBetween(before, Instant.now());
            assertThat(mapper.readTree(event.getDetails())).isEqualTo(mapper.readTree("""
                    {"before":{"lms_role":"STUDENT","access_status":"ACTIVE"},
                     "after":{"lms_role":"TUTOR","access_status":"SUSPENDED"}}
                    """));
        });
        var stored = users.findById(target.getUserId()).orElseThrow();
        for (String noop : List.of("{}", "{\"lms_role\":null,\"access_status\":null}",
                "{\"lms_role\":\"TUTOR\",\"access_status\":\"SUSPENDED\"}")) {
            json(send("PATCH", USERS + "/" + target.getUserId(), noop, "application/json", admin.getUserId()), 200);
        }
        assertThat(auditLogs.count()).isEqualTo(1);
        assertThat(users.findById(target.getUserId()).orElseThrow()).usingRecursiveComparison().isEqualTo(stored);
    }

    @Test
    void auditWriteFailureRollsBackAccessAndTimestamp() throws Exception {
        // Fail the real INSERT at the database boundary after the access row has been flushed.
        jdbc.execute("ALTER TABLE admin_user_http_test.audit_logs ADD CONSTRAINT reject_test_audit CHECK (action = 'REJECT')");
        try {
            error(send("PATCH", USERS + "/" + target.getUserId(),
                    "{\"lms_role\":\"TUTOR\",\"access_status\":\"SUSPENDED\"}", "application/json", admin.getUserId()),
                    500, "INTERNAL_SERVER_ERROR");
            assertUnchanged();
        } finally {
            jdbc.execute("ALTER TABLE admin_user_http_test.audit_logs DROP CONSTRAINT reject_test_audit");
        }
    }

    static Stream<Arguments> rejectedResourceMethods() {
        return Stream.of(USERS, "/api/admin/users").flatMap(route -> Stream.of(false, true)
                .flatMap(detail -> Stream.of("TRACE", "POST", "PUT", "DELETE", "PATCH")
                        .filter(method -> !detail || !method.equals("PATCH"))
                        .map(method -> Arguments.of(route, detail, method))));
    }

    @ParameterizedTest
    @MethodSource("rejectedResourceMethods")
    void rejectedMethodsAdvertiseOnlySupportedResourceMethods(String route, boolean detail, String method)
            throws Exception {
        String path = route + (detail ? "/" + target.getUserId() : "");
        var before = jdbc.queryForList("SELECT * FROM admin_user_http_test.lms_user_access ORDER BY user_id");
        var response = send(method, path, "{\"private\":\"method-body@example.test\"}",
                "application/json", admin.getUserId());
        error(response, 405, "METHOD_NOT_ALLOWED");
        assertThat(mapper.readTree(response.body())).isEqualTo(mapper.readTree("""
                {"code":"METHOD_NOT_ALLOWED","message":"Method Not Allowed","details":[]}
                """));
        assertThat(response.headers().allValues("Allow"))
                .containsExactly(detail ? "GET, HEAD, PATCH, OPTIONS" : "GET, HEAD, OPTIONS");
        assertThat(response.body()).doesNotContain("method-body", "Authorization", "Bearer");
        assertThat(jdbc.queryForList("SELECT * FROM admin_user_http_test.lms_user_access ORDER BY user_id"))
                .isEqualTo(before);
        assertUnchanged();
        var head = send("HEAD", path, null, "application/json", admin.getUserId());
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(send("OPTIONS", path, null, "application/json", null).statusCode()).isEqualTo(200);
        if (detail) {
            json(send("PATCH", path, "{\"lms_role\":\"TUTOR\"}", "application/json", admin.getUserId()), 200);
            assertThat(auditLogs.count()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"*/*", "text/html"})
    void anonymousTraceNeverReflectsHeadersOrBody(String accept) throws Exception {
        for (String route : List.of(USERS, "/api/admin/users")) {
            for (boolean detail : List.of(false, true)) {
                String path = route + (detail ? "/" + target.getUserId() : "");
                var anonymous = send("TRACE", path, null, accept, null);
                error(anonymous, 405, "METHOD_NOT_ALLOWED");
                assertThat(anonymous.headers().allValues("Allow"))
                        .containsExactly(detail ? "GET, HEAD, PATCH, OPTIONS" : "GET, HEAD, OPTIONS");
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(15)).header("Accept", accept)
                        .header("Authorization", "Bearer TRACE_SECRET_MARKER")
                        .header("X-Private", "TRACE_HEADER_MARKER")
                        .method("TRACE", HttpRequest.BodyPublishers.ofString("TRACE_BODY_MARKER")).build();
                var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                error(response, 405, "METHOD_NOT_ALLOWED");
                assertThat(response.headers().allValues("Allow"))
                        .containsExactly(detail ? "GET, HEAD, PATCH, OPTIONS" : "GET, HEAD, OPTIONS");
                assertThat(response.body()).doesNotContain("TRACE_SECRET_MARKER", "TRACE_HEADER_MARKER",
                        "TRACE_BODY_MARKER", "Authorization", "<html");
            }
        }
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {USERS, "/api/admin/users"})
    void malformedRequestAndMediaTypeNeverLogPii(String route, CapturedOutput output) throws Exception {
        String raw;
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15000);
            socket.getOutputStream().write(("GET " + route + "?q=[RAW_NAME_MARKER_raw@example.test HTTP/1.1\r\n"
                    + "Host: localhost\r\nAuthorization: Bearer RAW_TOKEN_MARKER\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(raw).startsWith("HTTP/1.1 400");
        assertThat(mapper.readTree(rawBody(raw))).isEqualTo(mapper.readTree("""
                {"code":"BAD_REQUEST","message":"Bad Request","details":[]}
                """));
        assertThat(raw.toLowerCase()).contains("content-type: application/json");
        assertThat(raw)
                .doesNotContain("RAW_NAME_MARKER", "raw@example.test", "RAW_TOKEN_MARKER", "<html");

        var claims = TOKENS.claims();
        claims.put("sub", admin.getUserId().toString());
        claims.put("name", "MEDIA_TOKEN_NAME_MARKER");
        String token = TOKENS.token(claims);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route + "/" + target.getUserId()))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "text/plain;note=\"MEDIA_NAME_MARKER media@example.test\"")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        error(response, 415, "UNSUPPORTED_MEDIA_TYPE");
        assertThat(mapper.readTree(response.body())).isEqualTo(mapper.readTree("""
                {"code":"UNSUPPORTED_MEDIA_TYPE","message":"Unsupported Media Type","details":[]}
                """));
        assertUnchanged();
        json(send("GET", route, null, "application/json", admin.getUserId()), 200);
        assertNoLogPii(output, "RAW_NAME_MARKER", "raw@example.test", "RAW_TOKEN_MARKER",
                "MEDIA_NAME_MARKER", "media@example.test", "MEDIA_TOKEN_NAME_MARKER", token);
    }

    @ParameterizedTest
    @ValueSource(strings = {USERS, "/api/admin/users"})
    void databaseFailureDoesNotLogIdentityProjection(String route, CapturedOutput output) throws Exception {
        jdbc.update("UPDATE admin_user_http_test.lms_user_access SET full_name = ?, email = ? WHERE user_id = ?",
                "DATABASE_NAME_MARKER", "database-pii@example.test", target.getUserId());
        target = users.findById(target.getUserId()).orElseThrow();
        jdbc.execute("ALTER TABLE admin_user_http_test.lms_user_access ADD CONSTRAINT reject_test_role CHECK (lms_role <> 'TUTOR')");
        try {
            var response = send("PATCH", route + "/" + target.getUserId(), "{\"lms_role\":\"TUTOR\"}",
                    "application/json", admin.getUserId());
            error(response, 500, "INTERNAL_SERVER_ERROR");
            assertThat(mapper.readTree(response.body())).isEqualTo(mapper.readTree("""
                    {"code":"INTERNAL_SERVER_ERROR","message":"An unexpected server error occurred.","details":[]}
                    """));
            assertUnchanged();
        } finally {
            jdbc.execute("ALTER TABLE admin_user_http_test.lms_user_access DROP CONSTRAINT reject_test_role");
        }
        assertNoLogPii(output, "DATABASE_NAME_MARKER", "database-pii@example.test", "Failing row contains", "Detail: Key");
        assertThat(output.getAll()).contains("23514");
        json(send("PATCH", route + "/" + target.getUserId(), "{\"lms_role\":\"TUTOR\"}",
                "application/json", admin.getUserId()), 200);
        assertThat(auditLogs.count()).isEqualTo(1);
    }

    private void assertNoLogPii(CapturedOutput output, String... markers) throws Exception {
        assertThat(output.getAll()).doesNotContain(markers);
        assertThat(Files.readString(Path.of("target/pii-http-regression.log"))).doesNotContain(markers);
    }

    private String rawBody(String response) {
        int split = response.indexOf("\r\n\r\n");
        String body = response.substring(split + 4);
        if (!response.substring(0, split).toLowerCase().contains("transfer-encoding: chunked")) {
            return body;
        }
        var decoded = new StringBuilder();
        int cursor = 0;
        while (true) {
            int end = body.indexOf("\r\n", cursor);
            int size = Integer.parseInt(body.substring(cursor, end).split(";", 2)[0], 16);
            if (size == 0) {
                return decoded.toString();
            }
            cursor = end + 2;
            decoded.append(body, cursor, cursor + size);
            cursor += size + 2;
        }
    }

    private HttpResponse<String> send(String method, String path, String body, String accept, UUID caller) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", accept);
        if (caller != null) {
            var claims = TOKENS.claims();
            claims.put("sub", caller.toString());
            claims.put("roles", List.of("ADMINISTRATOR"));
            request.header("Authorization", "Bearer " + TOKENS.token(claims));
        }
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        return mapper.readTree(response.body());
    }

    private void error(HttpResponse<String> response, int status, String code) {
        JsonNode body = json(response, status);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
        assertThat(body.path("details").isArray()).isTrue();
        assertThat(body.size()).isEqualTo(3);
    }

    private void assertProjection(JsonNode user, String role, String status) {
        assertThat(mapper.convertValue(user, Map.class).keySet()).containsExactlyInAnyOrder(
                "user_id", "full_name", "email", "last_login_at", "lms_role", "access_status", "created_at", "updated_at");
        assertThat(user.path("user_id").asText()).isEqualTo(target.getUserId().toString());
        assertThat(user.path("full_name").asText()).isEqualTo(target.getFullName());
        assertThat(user.path("email").asText()).isEqualTo(target.getEmail());
        assertThat(user.path("last_login_at").asText()).isEqualTo(ORIGINAL_TIME.toString());
        assertThat(user.path("created_at").asText()).isEqualTo(ORIGINAL_TIME.toString());
        assertThat(user.path("lms_role").asText()).isEqualTo(role);
        assertThat(user.path("access_status").asText()).isEqualTo(status);
    }

    private void assertUnchanged() {
        assertThat(users.findById(target.getUserId()).orElseThrow()).usingRecursiveComparison().isEqualTo(target);
        assertThat(auditLogs.count()).isZero();
    }

    private LmsUserAccess saveUser(String name, LmsRole role, AccessStatus status) {
        UUID id = UUID.randomUUID();
        return users.saveAndFlush(new LmsUserAccess(id, name, id + "@example.test", role, status,
                ORIGINAL_TIME, ORIGINAL_TIME, ORIGINAL_TIME));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtClockConfiguration {
        @Bean
        @Primary
        Clock fixedJwtClock() {
            return JwtTestSupport.CLOCK;
        }
    }
}
