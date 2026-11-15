package vn.edu.hcmut.co3103.lms.controller.admin;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;

import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual authenticated HTTP: admin advice must not change merged LMS-9 contracts. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Lms13ErrorContractPostgresHttpTest.Clocks.class)
class Lms13ErrorContractPostgresHttpTest {
    static final JwtTestSupport JWT = jwt();
    static JwtTestSupport jwt() { try { return new JwtTestSupport(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @TestConfiguration(proxyBeanMethods = false) static class Clocks {
        @Bean @Primary Clock clock() { return JwtTestSupport.CLOCK; }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> JwtTestSupport.ISSUER);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwksUri);
    }
    @AfterAll static void close() { JWT.close(); }
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;
    final List<UUID> users = new ArrayList<>();
    UUID student, tutor, admin;
    @BeforeEach void seed() {
        assertThat(db.queryForObject("SELECT version()", String.class)).contains("PostgreSQL 18");
        student = user("STUDENT"); tutor = user("TUTOR"); admin = user("ADMINISTRATOR");
    }
    @AfterEach void cleanup() { for (UUID id : users) db.update("DELETE FROM lms_user_access WHERE user_id=?", id); }

    @Test void studentMalformedRecommendationUuidKeepsMainContract() throws Exception {
        check("GET", "/classes/not-a-uuid/recommended-activities", null, student, 400,
                "INVALID_QUERY_PARAMETER", "A query parameter is invalid or unsupported.");
    }
    @Test void tutorMalformedClassAndMemberUuidsKeepMainContract() throws Exception {
        for (String path : List.of("/tutor/classes/not-a-uuid/members/" + UUID.randomUUID() + "/mastery",
                "/tutor/classes/" + UUID.randomUUID() + "/members/not-a-uuid/mastery")) {
            check("GET", path, null, tutor, 400, "INVALID_QUERY_PARAMETER", "A query parameter is invalid or unsupported.");
        }
    }
    @Test void adminMalformedUuidAndBodyKeepAdministrativeContracts() throws Exception {
        for (String path : List.of("/api/v1/admin/users", "/api/admin/users")) {
            check("GET", path + "/not-a-uuid", null, admin, 400, "BAD_REQUEST", "Bad Request");
            check("PATCH", path + "/" + student, "{\"lms_role\":\"INVALID\"}", admin, 400,
                    "VALIDATION_ERROR", "Validation failed");
        }
    }
    @Test void adminAuthorizationStillSeparatesGlobalIdentityFromLocalRole() throws Exception {
        check("GET", "/admin/users", null, null, 401, "UNAUTHORIZED", null);
        for (UUID id : List.of(student, tutor, UUID.randomUUID()))
            check("GET", "/admin/users", null, id, 403, "FORBIDDEN", null);
        db.update("UPDATE lms_user_access SET access_status='SUSPENDED' WHERE user_id=?", admin);
        check("GET", "/admin/users", null, admin, 403, "FORBIDDEN", null);
    }
    private UUID user(String role) {
        UUID id = UUID.randomUUID(); users.add(id);
        db.update("INSERT INTO lms_user_access(user_id,full_name,email,lms_role) VALUES (?,'Error contract',?,?)", id, id + "@example.test", role);
        return id;
    }
    private void check(String method, String path, String body, UUID user, int status, String code, String message) throws Exception {
        String absolute = path.startsWith("/api/") ? path : "/api/v1" + path;
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + absolute)).header("Content-Type", "application/json");
        if (user != null) { var claims = JWT.claims(); claims.put("sub", user.toString()); builder.header("Authorization", "Bearer " + JWT.token(claims)); }
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
            var json = mapper.readTree(response.body());
            assertThat(json.size()).isEqualTo(3); assertThat(json.get("code").asText()).isEqualTo(code);
            assertThat(json.get("details").isArray()).isTrue();
            if (message != null) assertThat(json.get("message").asText()).isEqualTo(message);
        }
    }
}
