package vn.edu.hcmut.co3103.lms.controller.classmanagement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises both merged modules through real HTTP, JWT verification and committed PostgreSQL rows. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ClassAdminIntegrationHttpTest.FixedClockConfiguration.class)
class ClassAdminIntegrationHttpTest {
    private static final JwtTestSupport TOKENS = startTokens();
    private static JwtTestSupport startTokens() {
        try { return new JwtTestSupport(); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> JwtTestSupport.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TOKENS::jwksUri);
    }

    @AfterAll static void closeTokens() { TOKENS.close(); }
    @LocalServerPort private int port;
    @Autowired private LmsUserAccessRepository users;
    @Autowired private ClassRepository classes;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    private final HttpClient client = HttpClient.newHttpClient();
    private UUID admin;
    private UUID tutor;
    private UUID student;
    private UUID classId;
    private String classCode;
    private String adminToken;
    private String tutorToken;
    private String studentToken;

    @BeforeEach
    void createFixtures() throws Exception {
        admin = createUser(LmsRole.ADMINISTRATOR);
        tutor = createUser(LmsRole.TUTOR);
        student = createUser(LmsRole.STUDENT);
        classCode = "MERGE-" + UUID.randomUUID().toString().substring(0, 8);
        classId = classes.saveAndFlush(new Class(tutor, classCode, "Integrated class", null, null,
                5, Class.EnrollmentPolicy.OPEN)).getClassId();
        adminToken = token(admin, null, null);
        tutorToken = token(tutor, null, null);
        studentToken = token(student, null, null);
    }

    @AfterEach
    void removeFixturesInForeignKeyOrder() {
        jdbc.update("DELETE FROM class_member WHERE class_id = ?", classId);
        if (classId != null) classes.deleteById(classId);
        jdbc.update("DELETE FROM audit_logs WHERE actor_id = ?", admin);
        for (UUID id : List.of(admin, tutor, student)) users.deleteById(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/users", "/api/admin/users"})
    void identicalMalformedInputsKeepEachControllersErrorContract(String adminPath) throws Exception {
        assertError(request("PATCH", adminPath + "/" + student, adminToken, "{", "application/json"),
                400, "BAD_REQUEST");
        assertError(request("PATCH", classPath(), tutorToken, "{", "application/json"), 400, "VALIDATION_ERROR");
        assertError(request("GET", adminPath + "/not-a-uuid", adminToken, null, null), 400, "BAD_REQUEST");
        assertError(request("GET", "/api/v1/classes/not-a-uuid", studentToken, null, null),
                400, "INVALID_QUERY_PARAMETER");
        assertError(request("PATCH", adminPath + "/" + student, adminToken,
                "{\"lms_role\":\"INVALID\"}", "application/json"), 400, "VALIDATION_ERROR");
        assertError(request("PATCH", classPath(), tutorToken,
                "{\"enrollment_policy\":\"INVALID\"}", "application/json"), 400, "VALIDATION_ERROR");

        var validation = request("POST", "/api/v1/classes", tutorToken, "{}", "application/json");
        assertError(validation, 400, "VALIDATION_ERROR");
        var details = json.readTree(validation.body()).get("details");
        assertThat(details.isEmpty()).isFalse();
        for (JsonNode detail : details) {
            assertThat(detail.size()).isEqualTo(2);
            assertThat(detail.has("field")).isTrue();
            assertThat(detail.get("code").asString()).isEqualTo("INVALID_VALUE");
        }
    }

    @Test
    void duplicateClassCodeKeepsItsConflictContract() throws Exception {
        assertError(request("POST", "/api/v1/classes", tutorToken,
                "{\"class_code\":\"" + classCode + "\",\"title\":\"Duplicate\",\"enrollment_policy\":\"OPEN\"}",
                "application/json"), 409, "CLASS_CODE_ALREADY_EXISTS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM class WHERE class_code = ?", Integer.class, classCode))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/users", "/api/admin/users"})
    void methodMediaAndNegotiationErrorsStayJsonWithoutAdminMutation(String adminPath) throws Exception {
        var before = jdbc.queryForMap("SELECT * FROM lms_user_access WHERE user_id = ?", student);
        var adminMethod = request("PUT", adminPath + "/" + student, adminToken, "{}", "application/json");
        assertError(adminMethod, 405, "METHOD_NOT_ALLOWED");
        assertThat(adminMethod.headers().firstValue("Allow").orElseThrow()).isEqualTo("GET, HEAD, PATCH, OPTIONS");
        var classMethod = request("PUT", classPath(), tutorToken, "{}", "application/json");
        assertError(classMethod, 405, "METHOD_NOT_ALLOWED");
        assertThat(classMethod.headers().firstValue("Allow").orElseThrow()).isEqualTo("GET, HEAD, PATCH, OPTIONS");
        for (String type : List.of("text/plain", "application/json;charset=unsupported-charset")) {
            assertError(request("PATCH", adminPath + "/" + student, adminToken, "{}", type),
                    415, "UNSUPPORTED_MEDIA_TYPE");
            assertError(request("PATCH", classPath(), tutorToken, "{}", type), 415, "UNSUPPORTED_MEDIA_TYPE");
        }
        for (String accept : List.of("text/html", "application/json;q=0", "application/json;charset=UTF-16")) {
            var response = client.send(builder(adminPath + "/" + student, adminToken)
                    .header("Accept", accept).header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"access_status\":\"SUSPENDED\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertError(response, 406, "NOT_ACCEPTABLE");
        }
        var classNegotiation = client.send(builder(classPath(), tutorToken).header("Accept", "text/html")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertError(classNegotiation, 406, "NOT_ACCEPTABLE");
        assertThat(jdbc.queryForMap("SELECT * FROM lms_user_access WHERE user_id = ?", student)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_id = ?", Integer.class, admin))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"access_status\":\"SUSPENDED\"}", "{\"lms_role\":\"STUDENT\"}"})
    void adminChangesTakeEffectOnTheSameTutorJwtAndHideCatalogAndDetail(String change) throws Exception {
        assertThat(request("POST", classPath() + "/membership", studentToken, null, null).statusCode()).isEqualTo(201);
        assertThat(request("PATCH", classPath(), tutorToken, "{\"title\":\"Before revoke\"}", "application/json")
                .statusCode()).isEqualTo(200);
        assertThat(catalog().get("total_items").asInt()).isEqualTo(1);
        assertThat(request("GET", classPath(), studentToken, null, null).statusCode()).isEqualTo(200);

        assertThat(request("PATCH", "/api/v1/admin/users/" + tutor, adminToken, change, "application/json")
                .statusCode()).isEqualTo(200);
        var before = jdbc.queryForMap("SELECT * FROM class WHERE class_id = ?", classId);
        assertError(request("PATCH", classPath(), tutorToken, "{\"title\":\"After revoke\"}", "application/json"),
                403, "FORBIDDEN");
        assertThat(catalog().get("total_items").asInt()).isZero();
        assertError(request("GET", classPath(), studentToken, null, null), 404, "CLASS_NOT_FOUND");
        assertThat(jdbc.queryForMap("SELECT * FROM class WHERE class_id = ?", classId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM class_member WHERE class_id = ? AND student_id = ?",
                String.class, classId, student)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_id = ? AND entity_id = ?",
                Integer.class, admin, tutor)).isEqualTo(1);
    }

    @Test
    void synchronizedProfilesAppearInCatalogDetailAndRosterSearchWithoutChangingIdentityOrAccess() throws Exception {
        assertThat(request("POST", classPath() + "/membership", studentToken, null, null).statusCode()).isEqualTo(201);
        String updatedTutor = token(tutor, "Updated Tutor", "updated.tutor@example.test");
        assertThat(request("GET", classPath(), updatedTutor, null, null).statusCode()).isEqualTo(200);
        var tutorItem = catalog().get("items").get(0).get("tutor");
        assertThat(tutorItem.get("user_id").asString()).isEqualTo(tutor.toString());
        assertThat(tutorItem.get("full_name").asString()).isEqualTo("Updated Tutor");
        var detail = request("GET", classPath(), studentToken, null, null);
        assertThat(json.readTree(detail.body()).get("tutor").get("email").asString())
                .isEqualTo("updated.tutor@example.test");

        String updatedStudent = token(student, "Updated Student", "updated.student@example.test");
        assertThat(request("GET", classPath(), updatedStudent, null, null).statusCode()).isEqualTo(200);
        for (String query : List.of("Updated%20Student", "updated.student%40example.test")) {
            var roster = request("GET", classPath() + "/members?q=" + query, tutorToken, null, null);
            assertThat(roster.statusCode()).isEqualTo(200);
            var page = json.readTree(roster.body());
            assertThat(page.get("total_items").asInt()).isEqualTo(1);
            assertThat(page.get("items").get(0).get("student_id").asString()).isEqualTo(student.toString());
        }
        var oldProfile = request("GET", classPath() + "/members?q=Existing", tutorToken, null, null);
        assertThat(oldProfile.statusCode()).isEqualTo(200);
        assertThat(json.readTree(oldProfile.body()).get("total_items").asInt()).isZero();
        assertThat(users.findById(tutor).orElseThrow().getLmsRole()).isEqualTo(LmsRole.TUTOR);
        assertThat(users.findById(student).orElseThrow().getLmsRole()).isEqualTo(LmsRole.STUDENT);
        assertThat(users.findById(student).orElseThrow().getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
    }

    private UUID createUser(LmsRole role) {
        UUID id = UUID.randomUUID();
        users.saveAndFlush(new LmsUserAccess(id, "Existing " + role, id + "@example.test", role,
                AccessStatus.ACTIVE, null, null, null));
        return id;
    }

    private String token(UUID user, String name, String email) throws Exception {
        var claims = TOKENS.claims();
        // Both controllers must use the same normalized UUID even for uppercase JWT subjects.
        claims.put("sub", user.toString().toUpperCase(Locale.ROOT));
        if (name != null) {
            claims.put("iat", JwtTestSupport.NOW.getEpochSecond() - 100);
            claims.put("fullName", name);
            claims.put("email", email);
        }
        return TOKENS.token(claims);
    }

    private String classPath() { return "/api/v1/classes/" + classId; }
    private JsonNode catalog() throws Exception {
        var response = request("GET", "/api/v1/classes?q=" + classCode, studentToken, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpRequest.Builder builder(String path, String token) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token);
    }

    private HttpResponse<String> request(String method, String path, String token, String body, String type)
            throws Exception {
        var builder = builder(path, token);
        if (type != null) builder.header("Content-Type", type);
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        var error = json.readTree(response.body());
        assertThat(error.size()).isEqualTo(3);
        assertThat(error.get("code").asString()).isEqualTo(code);
        assertThat(error.has("message")).isTrue();
        assertThat(error.get("details").isArray()).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean @Primary Clock fixedJwtClock() { return JwtTestSupport.CLOCK; }
    }
}
