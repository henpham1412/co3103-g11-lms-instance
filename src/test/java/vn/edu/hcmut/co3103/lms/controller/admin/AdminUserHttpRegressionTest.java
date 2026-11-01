package vn.edu.hcmut.co3103.lms.controller.admin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.repository.AuditLogRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

// No test-owned transaction: Tomcat completes each request, then assertions read committed state.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.schemas=admin_user_http_test",
        "spring.jpa.properties.hibernate.default_schema=admin_user_http_test"
})
@Import(AdminUserHttpRegressionTest.JwtClockConfiguration.class)
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
        admin = saveUser("Administrator", LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);
        target = saveUser("Nguyễn An", LmsRole.STUDENT, AccessStatus.ACTIVE);
    }

    @AfterEach
    void cleanFixtures() {
        auditLogs.deleteAllInBatch();
        users.deleteAllInBatch();
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

    static Stream<Arguments> unacceptableRepresentations() {
        return Stream.of("GET_LIST", "GET_DETAIL", "PATCH").flatMap(endpoint -> Stream.of(
                "application/json;charset=ISO-8859-1", "application/json;charset=UTF-16",
                "application/json;charset=windows-1252", "application/json;charset=UTF-32",
                "application/json;q=0", "*/*;q=0", "application/json;q=0, */*;q=1",
                "*/*;q=1, application/json;q=0", "application/*;q=0, */*;q=1",
                "application/json;charset=UTF-8;q=0, application/json;q=1",
                "application/problem+json", "application/xml", "text/html", "application/json;profile=custom")
                .map(accept -> Arguments.of(endpoint, accept)));
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
    @CsvSource({"page=-1", "page=0", "page=abc", "page=2147483648", "page=1.5", "page=",
            "size=-1", "size=0", "size=abc", "size=2147483648", "size=1.5", "size=",
            "page=2147483647&size=20", "page=1073741825&size=2", "page=1&page=2", "size=1&size=2"})
    void invalidPaginationIsRejected(String query) throws Exception {
        error(send("GET", USERS + "?" + query, null, "application/json", admin.getUserId()), 400, "BAD_REQUEST");
    }

    @Test
    void paginationDefaultsMaximumSizeAndTerminalMetadataAreCorrect() throws Exception {
        var defaults = json(send("GET", USERS, null, "application/json", admin.getUserId()), 200);
        assertThat(defaults.path("page").asInt()).isEqualTo(1);
        assertThat(defaults.path("size").asInt()).isEqualTo(20);
        var capped = json(send("GET", USERS + "?size=99999", null, "application/json", admin.getUserId()), 200);
        assertThat(capped.path("size").asInt()).isEqualTo(2000);
        var last = json(send("GET", USERS + "?page=2147483647&size=1", null,
                "application/json", admin.getUserId()), 200);
        assertThat(last.path("page").asLong()).isEqualTo(Integer.MAX_VALUE);
        assertThat(last.path("items").size()).isZero();
        assertThat(last.path("total_items").asInt()).isEqualTo(2);
        assertThat(last.path("total_pages").asInt()).isEqualTo(2);
        assertThat(last.path("last").asBoolean()).isTrue();
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
