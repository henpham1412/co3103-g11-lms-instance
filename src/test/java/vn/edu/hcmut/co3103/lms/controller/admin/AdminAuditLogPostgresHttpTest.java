package vn.edu.hcmut.co3103.lms.controller.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LMS-15 PostgreSQL + real HTTP integration tests for administrative audit logging and retrieval.
 * Scopes list queries by test actor_id so assertions remain deterministic on a shared test database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AdminAuditLogPostgresHttpTest.Clocks.class)
class AdminAuditLogPostgresHttpTest {

    private static final JwtTestSupport JWT = createJwtSupport();
    private static final String BASE = "/api/v1/admin/audit-logs";

    private static JwtTestSupport createJwtSupport() {
        try {
            return new JwtTestSupport();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        Clock clock() {
            return JwtTestSupport.CLOCK;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> JwtTestSupport.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwksUri);
        registry.add("lms.quiz.expiry-scan-ms", () -> "3600000");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate db;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<UUID> users = new ArrayList<>();
    private final List<UUID> classes = new ArrayList<>();
    private final List<UUID> reports = new ArrayList<>();
    private final List<UUID> insertedLogs = new ArrayList<>();

    private UUID adminId;
    private UUID secondAdminId;
    private UUID tutorId;
    private UUID studentId;

    @BeforeEach
    void seed() {
        adminId = user("ADMINISTRATOR");
        secondAdminId = user("ADMINISTRATOR");
        tutorId = user("TUTOR");
        studentId = user("STUDENT");
    }

    @AfterEach
    void cleanup() {
        for (UUID logId : insertedLogs) {
            db.update("DELETE FROM audit_logs WHERE audit_log_id = ?", logId);
        }
        for (UUID userId : users) {
            db.update("DELETE FROM audit_logs WHERE actor_id = ? OR entity_id = ?", userId, userId);
        }
        for (UUID reportId : reports) {
            db.update("DELETE FROM audit_logs WHERE entity_id = ?", reportId);
            db.update("DELETE FROM content_report WHERE report_id = ?", reportId);
        }
        for (UUID classId : classes) {
            db.update("DELETE FROM discussion WHERE class_id = ?", classId);
            db.update("DELETE FROM class_member WHERE class_id = ?", classId);
            db.update("DELETE FROM class WHERE class_id = ?", classId);
        }
        for (UUID userId : users) {
            db.update("DELETE FROM notification WHERE recipient_id = ?", userId);
            db.update("DELETE FROM lms_user_access WHERE user_id = ?", userId);
        }
    }

    @Test
    void userAccessMutationGeneratesRetrievableAuditLogAndNoOpDoesNotDuplicate() throws Exception {
        // 1. Real PATCH /api/v1/admin/users/{studentId}
        HttpResponse<String> patchRes = send("PATCH", "/api/v1/admin/users/" + studentId,
                "{\"lms_role\":\"TUTOR\",\"access_status\":\"SUSPENDED\"}", adminId);
        assertThat(patchRes.statusCode()).as(patchRes.body()).isEqualTo(200);

        // 2. No-op PATCH with same values must NOT create a duplicate audit entry
        HttpResponse<String> noopRes = send("PATCH", "/api/v1/admin/users/" + studentId,
                "{\"lms_role\":\"TUTOR\",\"access_status\":\"SUSPENDED\"}", adminId);
        assertThat(noopRes.statusCode()).isEqualTo(200);

        // 3. Retrieve via GET /api/v1/admin/audit-logs?actor_id=...
        JsonNode list = okJson(BASE + "?actor_id=" + adminId, adminId);
        assertThat(list.get("total_items").asLong()).isEqualTo(1);
        assertThat(list.get("items").size()).isEqualTo(1);

        JsonNode item = list.get("items").get(0);
        assertThat(item.get("actor_id").asText()).isEqualTo(adminId.toString());
        assertThat(item.get("action").asText()).isEqualTo("LMS_USER_ACCESS_UPDATED");
        assertThat(item.get("entity_type").asText()).isEqualTo("LmsUserAccess");
        assertThat(item.get("entity_id").asText()).isEqualTo(studentId.toString());
        assertThat(item.get("created_at").asText()).isNotBlank();
        assertThat(item.get("details").isObject()).isTrue();
        assertThat(item.get("details").get("before").get("lms_role").asText()).isEqualTo("STUDENT");
        assertThat(item.get("details").get("before").get("access_status").asText()).isEqualTo("ACTIVE");
        assertThat(item.get("details").get("after").get("lms_role").asText()).isEqualTo("TUTOR");
        assertThat(item.get("details").get("after").get("access_status").asText()).isEqualTo("SUSPENDED");
        assertThat(item.toString()).doesNotContain("@example.test", "Bearer");

        // 4. Retrieve single entry via GET /api/v1/admin/audit-logs/{logId}
        String logId = item.get("audit_log_id").asText();
        JsonNode single = okJson(BASE + "/" + logId, adminId);
        assertThat(single).isEqualTo(item);
    }

    @Test
    void contentModerationDecisionGeneratesRetrievableAuditLogAndFailedReviewDoesNot() throws Exception {
        UUID classId = newClass();
        UUID discussionId = newDiscussion(classId, studentId);
        UUID reportId = newContentReport(studentId, discussionId);

        // Invalid transition (PENDING -> PENDING) fails and creates 0 audit logs
        HttpResponse<String> invalid = send("PATCH", "/api/v1/admin/content-reports/" + reportId,
                "{\"status\":\"PENDING\"}", adminId);
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(okJson(BASE + "?actor_id=" + adminId, adminId).get("total_items").asLong()).isZero();

        // Valid transition (PENDING -> RESOLVED) creates 1 audit log
        HttpResponse<String> resolved = send("PATCH", "/api/v1/admin/content-reports/" + reportId,
                "{\"status\":\"RESOLVED\"}", adminId);
        assertThat(resolved.statusCode()).as(resolved.body()).isEqualTo(200);

        // Second transition on already-resolved report fails (409) and creates 0 additional audit logs
        HttpResponse<String> conflict = send("PATCH", "/api/v1/admin/content-reports/" + reportId,
                "{\"status\":\"DISMISSED\"}", adminId);
        assertThat(conflict.statusCode()).isEqualTo(409);

        JsonNode list = okJson(BASE + "?actor_id=" + adminId + "&entity_type=ContentReport", adminId);
        assertThat(list.get("total_items").asLong()).isEqualTo(1);
        JsonNode item = list.get("items").get(0);
        assertThat(item.get("action").asText()).isEqualTo("REPORTED_CONTENT_REVIEWED");
        assertThat(item.get("entity_type").asText()).isEqualTo("ContentReport");
        assertThat(item.get("entity_id").asText()).isEqualTo(reportId.toString());
        assertThat(item.get("details").get("before").get("status").asText()).isEqualTo("PENDING");
        assertThat(item.get("details").get("after").get("status").asText()).isEqualTo("RESOLVED");
        assertThat(item.toString()).doesNotContain("SENSITIVE_REPORT_BODY", "SECRET_DISCUSSION_TEXT");
    }

    @Test
    void filtersCombinedFiltersAndEmptyResultsWorkDeterministically() throws Exception {
        Instant t1 = Instant.parse("2026-10-10T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-11T10:00:00Z");
        Instant t3 = Instant.parse("2026-10-12T10:00:00Z");

        UUID log1 = insertAuditLog(adminId, "LMS_USER_ACCESS_UPDATED", "LmsUserAccess", studentId,
                "{\"before\":{\"lms_role\":\"STUDENT\",\"access_status\":\"ACTIVE\"},"
                        + "\"after\":{\"lms_role\":\"TUTOR\",\"access_status\":\"ACTIVE\"}}", t1);
        UUID log2 = insertAuditLog(adminId, "REPORTED_CONTENT_REVIEWED", "ContentReport", UUID.randomUUID(),
                "{\"before\":{\"status\":\"PENDING\"},\"after\":{\"status\":\"RESOLVED\"}}", t2);
        UUID log3 = insertAuditLog(secondAdminId, "LMS_USER_ACCESS_UPDATED", "LmsUserAccess", tutorId,
                "{\"before\":{\"lms_role\":\"TUTOR\",\"access_status\":\"ACTIVE\"},"
                        + "\"after\":{\"lms_role\":\"TUTOR\",\"access_status\":\"SUSPENDED\"}}", t3);

        // Filter by actor_id + action
        JsonNode byActorAction = okJson(BASE + "?actor_id=" + adminId + "&action=REPORTED_CONTENT_REVIEWED", adminId);
        assertThat(byActorAction.get("total_items").asLong()).isEqualTo(1);
        assertThat(byActorAction.get("items").get(0).get("audit_log_id").asText()).isEqualTo(log2.toString());

        // Filter by actor_id + from/to (inclusive bounds)
        JsonNode byRange = okJson(BASE + "?actor_id=" + adminId
                + "&from=" + enc("2026-10-10T10:00:00Z") + "&to=" + enc("2026-10-10T10:00:00Z"), adminId);
        assertThat(byRange.get("total_items").asLong()).isEqualTo(1);
        assertThat(byRange.get("items").get(0).get("audit_log_id").asText()).isEqualTo(log1.toString());

        // Filter by secondAdminId + entity_type
        JsonNode bySecond = okJson(BASE + "?actor_id=" + secondAdminId + "&entity_type=LmsUserAccess", adminId);
        assertThat(bySecond.get("total_items").asLong()).isEqualTo(1);
        assertThat(bySecond.get("items").get(0).get("audit_log_id").asText()).isEqualTo(log3.toString());

        // Empty result returns 200 OK with empty items and zero pagination totals
        JsonNode empty = okJson(BASE + "?actor_id=" + UUID.randomUUID(), adminId);
        assertThat(empty.get("items").isArray()).isTrue();
        assertThat(empty.get("items").size()).isZero();
        assertThat(empty.get("page").asInt()).isEqualTo(1);
        assertThat(empty.get("size").asInt()).isEqualTo(20);
        assertThat(empty.get("total_items").asLong()).isZero();
        assertThat(empty.get("total_pages").asInt()).isZero();
    }

    @Test
    void multiPagePaginationUsesDeterministicOrderForEqualTimestamps() throws Exception {
        Instant sameTime = Instant.parse("2026-10-14T12:00:00Z");
        String validDetails = "{\"before\":{\"lms_role\":\"STUDENT\",\"access_status\":\"ACTIVE\"},"
                + "\"after\":{\"lms_role\":\"TUTOR\",\"access_status\":\"ACTIVE\"}}";
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID id2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID id3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
        insertAuditLogWithId(id1, adminId, "LMS_USER_ACCESS_UPDATED", "LmsUserAccess", studentId, validDetails, sameTime);
        insertAuditLogWithId(id2, adminId, "LMS_USER_ACCESS_UPDATED", "LmsUserAccess", studentId, validDetails, sameTime);
        insertAuditLogWithId(id3, adminId, "LMS_USER_ACCESS_UPDATED", "LmsUserAccess", studentId, validDetails, sameTime);

        JsonNode page1 = okJson(BASE + "?actor_id=" + adminId + "&page=1&size=2", adminId);
        assertThat(page1.get("page").asInt()).isEqualTo(1);
        assertThat(page1.get("size").asInt()).isEqualTo(2);
        assertThat(page1.get("total_items").asLong()).isEqualTo(3);
        assertThat(page1.get("total_pages").asInt()).isEqualTo(2);
        assertThat(page1.get("items").get(0).get("audit_log_id").asText()).isEqualTo(id3.toString());
        assertThat(page1.get("items").get(1).get("audit_log_id").asText()).isEqualTo(id2.toString());

        JsonNode page2 = okJson(BASE + "?actor_id=" + adminId + "&page=2&size=2", adminId);
        assertThat(page2.get("items").size()).isEqualTo(1);
        assertThat(page2.get("items").get(0).get("audit_log_id").asText()).isEqualTo(id1.toString());
    }

    @Test
    void malformedOrSensitiveLegacyDetailsReturnNeutralFallbackInListAndSingleWithoutModifyingDb() throws Exception {
        String rawMalformed = "CORRUPTED_LEGACY_SYNTH_MARKER_1";
        String rawSensitiveJson = "{\"password\":\"SYNTH_PW_VALUE\",\"access_token\":\"SYNTH_AT_VALUE\","
                + "\"refresh_token\":\"SYNTH_RT_VALUE\"}";
        String rawNestedExtra = "{\"before\":{\"lms_role\":\"STUDENT\",\"access_status\":\"ACTIVE\","
                + "\"access_token\":\"SYNTH_NESTED_TOKEN\"},\"after\":{\"lms_role\":\"TUTOR\",\"access_status\":\"ACTIVE\"}}";

        UUID malformedId = insertAuditLog(adminId, "LMS_USER_ACCESS_UPDATED", "LmsUserAccess", studentId,
                rawMalformed, Instant.parse("2026-10-14T08:00:00Z"));
        UUID sensitiveId = insertAuditLog(adminId, "UNKNOWN_LEGACY_ACTION", "LmsUserAccess", studentId,
                rawSensitiveJson, Instant.parse("2026-10-14T08:01:00Z"));
        UUID nestedId = insertAuditLog(adminId, "LMS_USER_ACCESS_UPDATED", "LmsUserAccess", studentId,
                rawNestedExtra, Instant.parse("2026-10-14T08:02:00Z"));

        // Neither single-entry nor list retrieval leaks any synthetic sensitive content
        for (UUID id : List.of(malformedId, sensitiveId, nestedId)) {
            JsonNode single = okJson(BASE + "/" + id, adminId);
            assertThat(single.get("details").isObject()).isTrue();
            assertThat(single.get("details").size()).isEqualTo(1);
            assertThat(single.get("details").get("unavailable").asBoolean()).isTrue();
            assertThat(single.toString()).doesNotContain(
                    "SYNTH_MARKER_1", "SYNTH_PW_VALUE", "SYNTH_AT_VALUE", "SYNTH_RT_VALUE", "SYNTH_NESTED_TOKEN");
        }
        JsonNode list = okJson(BASE + "?actor_id=" + adminId, adminId);
        assertThat(list.get("total_items").asLong()).isEqualTo(3);
        assertThat(list.toString()).doesNotContain(
                "SYNTH_MARKER_1", "SYNTH_PW_VALUE", "SYNTH_AT_VALUE", "SYNTH_RT_VALUE", "SYNTH_NESTED_TOKEN");
        for (JsonNode item : list.get("items")) {
            assertThat(item.get("details").size()).isEqualTo(1);
            assertThat(item.get("details").get("unavailable").asBoolean()).isTrue();
        }

        // Original database rows must remain completely untouched
        assertThat(db.queryForObject("SELECT details FROM audit_logs WHERE audit_log_id = ?", String.class, malformedId))
                .isEqualTo(rawMalformed);
        assertThat(db.queryForObject("SELECT details FROM audit_logs WHERE audit_log_id = ?", String.class, sensitiveId))
                .isEqualTo(rawSensitiveJson);
        assertThat(db.queryForObject("SELECT details FROM audit_logs WHERE audit_log_id = ?", String.class, nestedId))
                .isEqualTo(rawNestedExtra);
    }

    @Test
    void invalidFiltersMalformedIdsAndMissingLogIdFollowErrorContract() throws Exception {
        List<String> invalidQueries = List.of(
                "actor_id=not-a-uuid",
                "actor_id=1-1-1-1-1",
                "actor_id=",
                "actor_id=" + adminId + "&actor_id=" + secondAdminId,
                "action=",
                "action=%20",
                "entity_type=",
                "from=not-a-date",
                "from=2026-10-14",
                "to=not-a-date",
                "from=" + enc("2026-10-15T00:00:00Z") + "&to=" + enc("2026-10-14T00:00:00Z"),
                "page=0",
                "page=-1",
                "page=abc",
                "size=0",
                "size=101",
                "unknown=1");
        for (String query : invalidQueries) {
            assertError(send("GET", BASE + "?" + query, null, adminId), 400, "VALIDATION_ERROR");
        }
        assertError(send("GET", BASE + "/not-a-uuid", null, adminId), 400, "VALIDATION_ERROR");
        assertError(send("GET", BASE + "/1-1-1-1-1", null, adminId), 400, "VALIDATION_ERROR");
        assertError(send("GET", BASE + "/" + UUID.randomUUID(), null, adminId), 404, "AUDIT_LOG_NOT_FOUND");
    }

    @Test
    void onlyActiveAdministratorsCanReadAndWriteMethodsAreRejected() throws Exception {
        UUID logId = insertAuditLog(adminId, "LMS_USER_ACCESS_UPDATED", "LmsUserAccess", studentId,
                "{\"before\":{\"lms_role\":\"STUDENT\"},\"after\":{\"lms_role\":\"TUTOR\"}}",
                Instant.parse("2026-10-14T09:00:00Z"));

        for (String path : List.of(BASE, BASE + "/" + logId)) {
            assertError(send("GET", path, null, null), 401, "UNAUTHORIZED");
            assertError(send("GET", path, null, studentId), 403, "FORBIDDEN");
            assertError(send("GET", path, null, tutorId), 403, "FORBIDDEN");
            assertError(send("GET", path, null, UUID.randomUUID()), 403, "FORBIDDEN");
            assertThat(send("GET", path, null, adminId).statusCode()).isEqualTo(200);
        }

        // Write methods on audit-logs must be rejected for even an active ADMINISTRATOR
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            assertThat(send(method, BASE, "{}", adminId).statusCode()).isEqualTo(405);
            assertThat(send(method, BASE + "/" + logId, "{}", adminId).statusCode()).isEqualTo(405);
        }

        // Suspended administrator is denied
        db.update("UPDATE lms_user_access SET access_status = 'SUSPENDED' WHERE user_id = ?", adminId);
        assertError(send("GET", BASE, null, adminId), 403, "FORBIDDEN");
        assertError(send("GET", BASE + "/" + logId, null, adminId), 403, "FORBIDDEN");
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode okJson(String path, UUID userId) throws Exception {
        HttpResponse<String> response = send("GET", path, null, userId);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private HttpResponse<String> send(String method, String path, String body, UUID userId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "application/json");
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (userId != null) {
            var claims = JWT.claims();
            claims.put("sub", userId.toString());
            builder.header("Authorization", "Bearer " + JWT.token(claims));
        }
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("code").asText()).isEqualTo(code);
        assertThat(json.get("details").isArray()).isTrue();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        users.add(id);
        db.update("INSERT INTO lms_user_access(user_id, full_name, email, lms_role, access_status) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE')", id, "Audit " + role, id + "@example.test", role);
        return id;
    }

    private UUID newClass() {
        UUID id = UUID.randomUUID();
        classes.add(id);
        db.update("INSERT INTO class(class_id, tutor_id, class_code, title, enrollment_policy, status) "
                + "VALUES (?, ?, ?, 'Audit Class', 'OPEN', 'ACTIVE')",
                id, tutorId, "A" + id.toString().substring(0, 8));
        return id;
    }

    private UUID newDiscussion(UUID classId, UUID authorId) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO discussion(discussion_id, class_id, author_id, title, content) "
                + "VALUES (?, ?, ?, 'Audit Thread', 'SECRET_DISCUSSION_TEXT')", id, classId, authorId);
        return id;
    }

    private UUID newContentReport(UUID reporterId, UUID discussionId) {
        UUID id = UUID.randomUUID();
        reports.add(id);
        db.update("INSERT INTO content_report(report_id, reporter_id, content_type, content_id, reason, description, status) "
                + "VALUES (?, ?, 'DISCUSSION', ?, 'SPAM', 'SENSITIVE_REPORT_BODY', 'PENDING')",
                id, reporterId, discussionId);
        return id;
    }

    private UUID insertAuditLog(UUID actorId, String action, String entityType, UUID entityId,
                                String details, Instant createdAt) {
        UUID id = UUID.randomUUID();
        return insertAuditLogWithId(id, actorId, action, entityType, entityId, details, createdAt);
    }

    private UUID insertAuditLogWithId(UUID id, UUID actorId, String action, String entityType, UUID entityId,
                                      String details, Instant createdAt) {
        insertedLogs.add(id);
        db.update("INSERT INTO audit_logs(audit_log_id, actor_id, action, entity_type, entity_id, details, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, actorId, action, entityType, entityId, details, Timestamp.from(createdAt));
        return id;
    }
}
