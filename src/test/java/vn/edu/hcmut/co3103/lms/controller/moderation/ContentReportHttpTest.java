package vn.edu.hcmut.co3103.lms.controller.moderation;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Content reporting and moderation over a real servlet container and the real PostgreSQL schema
 * (DOC-05 sections 3.5 and 5.3). Reporter identity, target access, the review transition and the
 * deletion guards are only observable end to end: a mocked service would not prove that a denied
 * caller stored nothing, or that a resolved report really released its target.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ContentReportHttpTest.Clocks.class)
class ContentReportHttpTest {

    private static final JwtTestSupport JWT = createJwtSupport();

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
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate db;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> classIds = new ArrayList<>();

    private UUID tutorId;
    private UUID otherTutorId;
    private UUID studentId;
    private UUID secondStudentId;
    private UUID pendingStudentId;
    private UUID leftStudentId;
    private UUID outsiderId;
    private UUID adminId;
    private UUID suspendedAdminId;
    private UUID suspendedStudentId;
    private UUID classId;
    private UUID otherClassId;
    private UUID lessonId;
    private UUID otherLessonId;
    private UUID materialId;
    private UUID hiddenMaterialId;
    private String tutorToken;
    private String otherTutorToken;
    private String studentToken;
    private String secondStudentToken;
    private String pendingToken;
    private String leftToken;
    private String outsiderToken;
    private String adminToken;
    private String suspendedAdminToken;
    private String suspendedStudentToken;

    @BeforeEach
    void seed() throws Exception {
        tutorId = insertUser("TUTOR", "ACTIVE");
        otherTutorId = insertUser("TUTOR", "ACTIVE");
        studentId = insertUser("STUDENT", "ACTIVE");
        secondStudentId = insertUser("STUDENT", "ACTIVE");
        pendingStudentId = insertUser("STUDENT", "ACTIVE");
        leftStudentId = insertUser("STUDENT", "ACTIVE");
        outsiderId = insertUser("STUDENT", "ACTIVE");
        adminId = insertUser("ADMINISTRATOR", "ACTIVE");
        suspendedAdminId = insertUser("ADMINISTRATOR", "SUSPENDED");
        suspendedStudentId = insertUser("STUDENT", "SUSPENDED");

        tutorToken = tokenFor(tutorId);
        otherTutorToken = tokenFor(otherTutorId);
        studentToken = tokenFor(studentId);
        secondStudentToken = tokenFor(secondStudentId);
        pendingToken = tokenFor(pendingStudentId);
        leftToken = tokenFor(leftStudentId);
        outsiderToken = tokenFor(outsiderId);
        adminToken = tokenFor(adminId);
        suspendedAdminToken = tokenFor(suspendedAdminId);
        suspendedStudentToken = tokenFor(suspendedStudentId);

        classId = insertClass(tutorId);
        otherClassId = insertClass(otherTutorId);
        lessonId = insertLesson(insertChapter(classId));
        otherLessonId = insertLesson(insertChapter(otherClassId));
        materialId = insertMaterial(lessonId, "Published notes", "PUBLISHED");
        hiddenMaterialId = insertMaterial(lessonId, "Hidden notes", "HIDDEN");

        insertMember(classId, studentId, "ACTIVE");
        insertMember(classId, secondStudentId, "ACTIVE");
        insertMember(classId, pendingStudentId, "PENDING");
        insertMember(classId, leftStudentId, "INACTIVE");
        insertMember(classId, suspendedStudentId, "ACTIVE");
    }

    @AfterEach
    void cleanup() {
        for (UUID id : userIds) {
            db.update("DELETE FROM content_report WHERE reporter_id = ? OR reviewed_by = ?", id, id);
        }
        for (UUID id : classIds) {
            db.update("DELETE FROM discussion WHERE class_id = ? AND parent_id IS NOT NULL", id);
            db.update("DELETE FROM discussion WHERE class_id = ?", id);
            db.update("DELETE FROM material WHERE lesson_id IN "
                    + "(SELECT lesson_id FROM lesson WHERE chapter_id IN "
                    + "(SELECT chapter_id FROM chapter WHERE class_id = ?))", id);
            db.update("DELETE FROM class_member WHERE class_id = ?", id);
            db.update("DELETE FROM lesson WHERE chapter_id IN (SELECT chapter_id FROM chapter WHERE class_id = ?)", id);
            db.update("DELETE FROM chapter WHERE class_id = ?", id);
            db.update("DELETE FROM class WHERE class_id = ?", id);
        }
        for (UUID id : userIds) {
            db.update("DELETE FROM lms_user_access WHERE user_id = ?", id);
        }
        classIds.clear();
        userIds.clear();
    }

    @Test
    void aStudentReportIsPersistedWithTheTokenReporterAndAPendingStatus() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Question\",\"content\":\"body\"}");

        HttpResponse<String> created = post("/content-reports", studentToken,
                reportBody("DISCUSSION", discussionId, "INAPPROPRIATE", "This post is offensive."));

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = json(created);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "report_id", "content_type", "content_id", "reason", "status", "created_at");
        assertThat(body.get("content_type").asString()).isEqualTo("DISCUSSION");
        assertThat(body.get("content_id").asString()).isEqualTo(discussionId.toString());
        assertThat(body.get("reason").asString()).isEqualTo("INAPPROPRIATE");
        assertThat(body.get("status").asString()).isEqualTo("PENDING");
        assertThat(body.get("created_at").isNull()).isFalse();

        UUID reportId = UUID.fromString(body.get("report_id").asString());
        Map<String, Object> row = reportRow(reportId);
        assertThat(row.get("reporter_id")).isEqualTo(studentId);
        assertThat(row.get("content_type")).isEqualTo("DISCUSSION");
        assertThat(row.get("content_id")).isEqualTo(discussionId);
        assertThat(row.get("reason")).isEqualTo("INAPPROPRIATE");
        assertThat(row.get("description")).isEqualTo("This post is offensive.");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("reviewed_by")).isNull();
        assertThat(row.get("reviewed_at")).isNull();
        assertThat(row.get("created_at")).isNotNull();

        // BR-6.5: reporting never hides the target.
        assertThat(get("/discussions/" + discussionId, studentToken).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT status FROM material WHERE material_id = ?",
                String.class, materialId)).isEqualTo("PUBLISHED");
    }

    @Test
    void aTutorReportsMaterialOfTheirOwnClassAndTheMaterialStaysPublished() throws Exception {
        HttpResponse<String> created = post("/content-reports", tutorToken,
                reportBody("MATERIAL", materialId, "SPAM", null));

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = json(created);
        assertThat(body.get("status").asString()).isEqualTo("PENDING");
        UUID reportId = UUID.fromString(body.get("report_id").asString());
        Map<String, Object> row = reportRow(reportId);
        assertThat(row.get("reporter_id")).isEqualTo(tutorId);
        assertThat(row.get("content_type")).isEqualTo("MATERIAL");
        assertThat(row.get("content_id")).isEqualTo(materialId);
        assertThat(row.get("reason")).isEqualTo("SPAM");
        assertThat(row.get("description")).isNull();

        assertThat(get("/materials/" + materialId, studentToken).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT status FROM material WHERE material_id = ?",
                String.class, materialId)).isEqualTo("PUBLISHED");
    }

    @Test
    void everyDocumentedReasonIsAcceptedAndAnOptionalDescriptionMayBeOmitted() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Reasons\",\"content\":\"body\"}");
        for (String reason : List.of("SPAM", "HARASSMENT", "INAPPROPRIATE", "OTHER")) {
            UUID target = createThread(studentToken, "{\"title\":\"" + reason + "\",\"content\":\"body\"}");
            HttpResponse<String> created = post("/content-reports", studentToken,
                    "{\"content_type\":\"DISCUSSION\",\"content_id\":\"" + target + "\",\"reason\":\"" + reason + "\"}");
            assertThat(created.statusCode()).as("reason %s", reason).isEqualTo(201);
            assertThat(json(created).get("reason").asString()).isEqualTo(reason);
        }
        assertThat(post("/content-reports", studentToken,
                reportBody("DISCUSSION", discussionId, "OTHER", "  padded  ")).statusCode()).isEqualTo(201);
        assertThat(reportCount()).isEqualTo(5L);
    }

    @Test
    void theReporterAndTheReviewOutcomeCannotBeInjectedThroughTheBody() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Whose report\",\"content\":\"body\"}");
        String target = "\"content_type\":\"DISCUSSION\",\"content_id\":\"" + discussionId
                + "\",\"reason\":\"SPAM\"";

        for (String injection : List.of(
                "\"reporter_id\":\"" + tutorId + "\"",
                "\"report_id\":\"" + UUID.randomUUID() + "\"",
                "\"status\":\"RESOLVED\"",
                "\"reviewed_by\":\"" + adminId + "\"",
                "\"reviewed_at\":\"2026-01-01T00:00:00Z\"",
                "\"created_at\":\"2026-01-01T00:00:00Z\"")) {
            HttpResponse<String> response = post("/content-reports", studentToken, "{" + target + "," + injection + "}");
            assertThat(response.statusCode()).as("injection %s", injection).isEqualTo(400);
            assertThat(code(response)).as("injection %s", injection).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(reportCount()).isZero();
    }

    @Test
    void reportInputsAreValidatedBeforeAnythingIsStored() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Valid target\",\"content\":\"body\"}");
        String valid = "\"content_type\":\"DISCUSSION\",\"content_id\":\"" + discussionId + "\",\"reason\":\"SPAM\"";

        List<String> invalidBodies = List.of(
                "{}",
                "{\"content_id\":\"" + discussionId + "\",\"reason\":\"SPAM\"}",
                "{\"content_type\":\"DISCUSSION\",\"reason\":\"SPAM\"}",
                "{\"content_type\":\"DISCUSSION\",\"content_id\":\"" + discussionId + "\"}",
                "{" + valid + ",\"description\":\"   \"}",
                "{\"content_type\":\"VIDEO\",\"content_id\":\"" + discussionId + "\",\"reason\":\"SPAM\"}",
                "{\"content_type\":\"discussion\",\"content_id\":\"" + discussionId + "\",\"reason\":\"SPAM\"}",
                "{\"content_type\":0,\"content_id\":\"" + discussionId + "\",\"reason\":\"SPAM\"}",
                "{\"content_type\":true,\"content_id\":\"" + discussionId + "\",\"reason\":\"SPAM\"}",
                "{\"content_type\":\"DISCUSSION\",\"content_id\":\"" + discussionId + "\",\"reason\":\"BULLYING\"}",
                "{\"content_type\":\"DISCUSSION\",\"content_id\":\"" + discussionId + "\",\"reason\":true}",
                "{\"content_type\":\"DISCUSSION\",\"content_id\":\"" + discussionId + "\",\"reason\":1}",
                "{\"content_type\":\"DISCUSSION\",\"content_id\":\"not-a-uuid\",\"reason\":\"SPAM\"}",
                "{\"content_type\":\"DISCUSSION\",\"content_id\":123,\"reason\":\"SPAM\"}",
                "{\"content_type\":\"DISCUSSION\",\"content_id\":\"" + discussionId + "\",\"reason\":\"SPAM\","
                        + "\"description\":123}");

        for (String body : invalidBodies) {
            HttpResponse<String> response = post("/content-reports", studentToken, body);
            assertThat(response.statusCode()).as("body %s", body).isEqualTo(400);
            assertThat(code(response)).as("body %s", body).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(reportCount()).isZero();
    }

    @Test
    void aNonexistentTargetIsReportedAsMissingContent() throws Exception {
        for (String type : List.of("DISCUSSION", "MATERIAL")) {
            HttpResponse<String> response = post("/content-reports", studentToken,
                    reportBody(type, UUID.randomUUID(), "OTHER", null));
            assertThat(response.statusCode()).as("type %s", type).isEqualTo(404);
            assertThat(code(response)).as("type %s", type).isEqualTo("REPORTED_CONTENT_NOT_FOUND");
        }
        assertThat(reportCount()).isZero();
    }

    @Test
    void onlyACallerWhoMayReadTheTargetCanReportIt() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Members only\",\"content\":\"body\"}");
        UUID foreignDiscussion = createThread(otherTutorToken, otherClassId,
                "{\"title\":\"Other class\",\"content\":\"body\"}");

        for (String token : List.of(outsiderToken, pendingToken, leftToken, otherTutorToken)) {
            HttpResponse<String> response = post("/content-reports", token,
                    reportBody("DISCUSSION", discussionId, "SPAM", null));
            assertThat(response.statusCode()).as("caller %s", token).isEqualTo(403);
            assertThat(code(response)).as("caller %s", token).isEqualTo("FORBIDDEN");
        }
        assertThat(code(post("/content-reports", studentToken,
                reportBody("DISCUSSION", foreignDiscussion, "SPAM", null)))).isEqualTo("FORBIDDEN");
        assertThat(code(post("/content-reports", studentToken,
                reportBody("MATERIAL", hiddenMaterialId, "SPAM", null)))).isEqualTo("REPORTED_CONTENT_NOT_FOUND");
        assertThat(post("/content-reports", tutorToken,
                reportBody("MATERIAL", hiddenMaterialId, "SPAM", null)).statusCode()).isEqualTo(201);
        assertThat(reportCount()).isEqualTo(1L);
    }

    @Test
    void aSuspendedAccountAndAnAdministratorCannotSubmitReports() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Target\",\"content\":\"body\"}");

        assertThat(post("/content-reports", suspendedStudentToken,
                reportBody("DISCUSSION", discussionId, "SPAM", null)).statusCode()).isEqualTo(403);
        HttpResponse<String> administrator = post("/content-reports", adminToken,
                reportBody("DISCUSSION", discussionId, "SPAM", null));
        assertThat(administrator.statusCode()).isEqualTo(403);
        assertThat(code(administrator)).isEqualTo("FORBIDDEN");
        assertThat(reportCount()).isZero();
    }

    @Test
    void anUnauthenticatedRequestCannotCreateAReport() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Target\",\"content\":\"body\"}");
        String body = reportBody("DISCUSSION", discussionId, "SPAM", null);

        HttpResponse<String> anonymous = post("/content-reports", null, body);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(code(anonymous)).isEqualTo("UNAUTHORIZED");
        assertThat(post("/content-reports", "not-a-jwt", body).statusCode()).isEqualTo(401);
        assertThat(reportCount()).isZero();
    }

    @Test
    void duplicateReportsAreBlockedPerReporterAndTarget() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Target\",\"content\":\"body\"}");

        assertThat(post("/content-reports", studentToken,
                reportBody("DISCUSSION", discussionId, "SPAM", "first")).statusCode()).isEqualTo(201);
        HttpResponse<String> duplicate = post("/content-reports", studentToken,
                reportBody("DISCUSSION", discussionId, "HARASSMENT", "second"));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(code(duplicate)).isEqualTo("DUPLICATE_CONTENT_REPORT");
        assertThat(reportCount()).isEqualTo(1L);
        assertThat(db.queryForObject("SELECT reason FROM content_report WHERE content_id = ?",
                String.class, discussionId)).isEqualTo("SPAM");

        // The uniqueness rule is per reporter and per target, not global.
        assertThat(post("/content-reports", secondStudentToken,
                reportBody("DISCUSSION", discussionId, "SPAM", null)).statusCode()).isEqualTo(201);
        assertThat(post("/content-reports", studentToken,
                reportBody("MATERIAL", materialId, "SPAM", null)).statusCode()).isEqualTo(201);
        assertThat(reportCount()).isEqualTo(3L);
    }

    @Test
    void onlyAnAdministratorCanListReports() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Target\",\"content\":\"body\"}");
        UUID reportId = createReport(studentToken, "DISCUSSION", discussionId);

        for (String token : List.of(studentToken, tutorToken, outsiderToken, suspendedAdminToken)) {
            HttpResponse<String> response = get("/admin/content-reports", token);
            assertThat(response.statusCode()).as("caller %s", token).isEqualTo(403);
            assertThat(code(response)).as("caller %s", token).isEqualTo("FORBIDDEN");
        }
        assertThat(get("/admin/content-reports", null).statusCode()).isEqualTo(401);

        HttpResponse<String> listed = get("/admin/content-reports", adminToken);
        assertThat(listed.statusCode()).isEqualTo(200);
        JsonNode page = json(listed);
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("total_items").asLong()).isEqualTo(1L);
        assertThat(page.get("total_pages").asInt()).isEqualTo(1);
        assertThat(page.get("items")).hasSize(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("report_id").asString()).isEqualTo(reportId.toString());
        assertThat(item.get("reporter_id").asString()).isEqualTo(studentId.toString());
        assertThat(item.get("status").asString()).isEqualTo("PENDING");
        assertThat(item.get("target_available").asBoolean()).isTrue();
    }

    @Test
    void theAdministratorListFiltersByStatusAndContentTypeAndPaginates() throws Exception {
        UUID first = createThread(studentToken, "{\"title\":\"First\",\"content\":\"body\"}");
        UUID second = createThread(studentToken, "{\"title\":\"Second\",\"content\":\"body\"}");
        UUID third = createThread(secondStudentToken, "{\"title\":\"Third\",\"content\":\"body\"}");
        createReport(studentToken, "DISCUSSION", first);
        UUID materialReport = createReport(studentToken, "MATERIAL", materialId);
        UUID hiddenMaterialReport = createReport(tutorToken, "MATERIAL", hiddenMaterialId);
        createReport(secondStudentToken, "DISCUSSION", second);
        UUID resolved = createReport(studentToken, "DISCUSSION", third);
        assertThat(patch("/admin/content-reports/" + resolved, adminToken, "{\"status\":\"DISMISSED\"}")
                .statusCode()).isEqualTo(200);

        JsonNode pending = json(get("/admin/content-reports?status=PENDING", adminToken));
        assertThat(pending.get("total_items").asLong()).isEqualTo(4L);
        assertThat(reportIds(pending)).doesNotContain(resolved);

        JsonNode materials = json(get("/admin/content-reports?content_type=MATERIAL", adminToken));
        assertThat(materials.get("total_items").asLong()).isEqualTo(2L);
        assertThat(reportIds(materials)).containsExactlyInAnyOrder(materialReport, hiddenMaterialReport);

        JsonNode dismissed = json(get("/admin/content-reports?status=DISMISSED&content_type=DISCUSSION", adminToken));
        assertThat(dismissed.get("total_items").asLong()).isEqualTo(1L);
        assertThat(reportIds(dismissed)).containsExactly(resolved);

        JsonNode firstPage = json(get("/admin/content-reports?page=1&size=2", adminToken));
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(firstPage.get("total_pages").asInt()).isEqualTo(3);
        JsonNode secondPage = json(get("/admin/content-reports?page=2&size=2", adminToken));
        assertThat(secondPage.get("items")).hasSize(2);
        assertThat(reportIds(firstPage)).doesNotContainAnyElementsOf(reportIds(secondPage));
        assertThat(json(get("/admin/content-reports?page=9&size=2", adminToken)).get("items")).isEmpty();

        for (String query : List.of("?page=0", "?page=-1", "?size=0", "?size=101")) {
            HttpResponse<String> response = get("/admin/content-reports" + query, adminToken);
            assertThat(response.statusCode()).as("query %s", query).isEqualTo(400);
            assertThat(code(response)).as("query %s", query).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(get("/admin/content-reports?status=PENDING&page=1&size=100", adminToken).statusCode())
                .isEqualTo(200);
    }

    @Test
    void onlyAnAdministratorCanReadOneReportAndMissingReportsAreNotFound() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Target\",\"content\":\"body\"}");
        UUID reportId = createReport(studentToken, "DISCUSSION", discussionId);

        for (String token : List.of(studentToken, tutorToken, suspendedAdminToken)) {
            HttpResponse<String> response = get("/admin/content-reports/" + reportId, token);
            assertThat(response.statusCode()).as("caller %s", token).isEqualTo(403);
            assertThat(code(response)).as("caller %s", token).isEqualTo("FORBIDDEN");
        }
        assertThat(get("/admin/content-reports/" + reportId, null).statusCode()).isEqualTo(401);

        HttpResponse<String> detail = get("/admin/content-reports/" + reportId, adminToken);
        assertThat(detail.statusCode()).isEqualTo(200);
        JsonNode body = json(detail);
        assertThat(body.get("report_id").asString()).isEqualTo(reportId.toString());
        assertThat(body.get("reporter_id").asString()).isEqualTo(studentId.toString());
        assertThat(body.get("content_type").asString()).isEqualTo("DISCUSSION");
        assertThat(body.get("content_id").asString()).isEqualTo(discussionId.toString());
        assertThat(body.get("reason").asString()).isEqualTo("SPAM");
        assertThat(body.get("status").asString()).isEqualTo("PENDING");
        assertThat(body.get("reviewed_by").isNull()).isTrue();
        assertThat(body.get("reviewed_at").isNull()).isTrue();
        assertThat(body.get("created_at").isNull()).isFalse();
        assertThat(body.get("target_available").asBoolean()).isTrue();

        HttpResponse<String> missing = get("/admin/content-reports/" + UUID.randomUUID(), adminToken);
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("CONTENT_REPORT_NOT_FOUND");
    }

    @Test
    void eachPermittedOutcomeIsRecordedOnceWithTheReviewingAdministrator() throws Exception {
        for (String outcome : List.of("REVIEWED", "RESOLVED", "DISMISSED")) {
            UUID discussionId = createThread(studentToken, "{\"title\":\"" + outcome + "\",\"content\":\"body\"}");
            UUID reportId = createReport(studentToken, "DISCUSSION", discussionId);

            HttpResponse<String> reviewed = patch("/admin/content-reports/" + reportId, adminToken,
                    "{\"status\":\"" + outcome + "\"}");
            assertThat(reviewed.statusCode()).as("outcome %s", outcome).isEqualTo(200);
            JsonNode body = json(reviewed);
            assertThat(body.get("status").asString()).isEqualTo(outcome);
            assertThat(body.get("reviewed_by").asString()).isEqualTo(adminId.toString());
            assertThat(body.get("reviewed_at").isNull()).isFalse();

            Map<String, Object> row = reportRow(reportId);
            assertThat(row.get("status")).isEqualTo(outcome);
            assertThat(row.get("reviewed_by")).isEqualTo(adminId);
            assertThat(row.get("reviewed_at")).isNotNull();
            assertThat(row.get("reporter_id")).isEqualTo(studentId);
            assertThat(row.get("content_id")).isEqualTo(discussionId);
            assertThat(row.get("reason")).isEqualTo("SPAM");

            HttpResponse<String> again = patch("/admin/content-reports/" + reportId, adminToken,
                    "{\"status\":\"REVIEWED\"}");
            assertThat(again.statusCode()).as("outcome %s", outcome).isEqualTo(409);
            assertThat(code(again)).isEqualTo("REPORT_ALREADY_UPDATED");
            assertThat(reportRow(reportId).get("status")).isEqualTo(outcome);
            assertThat(reportRow(reportId).get("reviewed_by")).isEqualTo(adminId);
        }
    }

    @Test
    void invalidStatusValuesAndExtraFieldsAreRejectedWithoutChangingTheReport() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Target\",\"content\":\"body\"}");
        UUID reportId = createReport(studentToken, "DISCUSSION", discussionId);
        String path = "/admin/content-reports/" + reportId;

        for (String body : List.of(
                "{\"status\":\"PENDING\"}",
                "{\"status\":\"APPROVED\"}",
                "{\"status\":\"REJECTED\"}",
                "{\"status\":\"resolved\"}",
                "{\"status\":\"\"}",
                "{\"status\":123}",
                "{\"status\":true}",
                "{\"status\":null}",
                "{}",
                "{\"reviewed_by\":\"" + adminId + "\"}",
                "{\"status\":\"RESOLVED\",\"reviewed_by\":\"" + studentId + "\"}",
                "{\"status\":\"RESOLVED\",\"reporter_id\":\"" + tutorId + "\"}")) {
            HttpResponse<String> response = patch(path, adminToken, body);
            assertThat(response.statusCode()).as("body %s", body).isEqualTo(400);
            assertThat(code(response)).as("body %s", body).isEqualTo("INVALID_REPORT_STATUS");
        }
        Map<String, Object> row = reportRow(reportId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("reviewed_by")).isNull();
        assertThat(row.get("reviewed_at")).isNull();
    }

    @Test
    void aReporterCannotReviewTheirOwnReportAndNoOtherRoleCanEither() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Target\",\"content\":\"body\"}");
        UUID reportId = createReport(studentToken, "DISCUSSION", discussionId);
        String path = "/admin/content-reports/" + reportId;

        for (String token : List.of(studentToken, secondStudentToken, tutorToken, otherTutorToken,
                outsiderToken, suspendedAdminToken)) {
            HttpResponse<String> response = patch(path, token, "{\"status\":\"RESOLVED\"}");
            assertThat(response.statusCode()).as("caller %s", token).isEqualTo(403);
            assertThat(code(response)).as("caller %s", token).isEqualTo("FORBIDDEN");
        }
        assertThat(patch(path, null, "{\"status\":\"RESOLVED\"}").statusCode()).isEqualTo(401);
        assertThat(patch(path, "not-a-jwt", "{\"status\":\"RESOLVED\"}").statusCode()).isEqualTo(401);

        Map<String, Object> row = reportRow(reportId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("reviewed_by")).isNull();
        assertThat(row.get("reviewed_at")).isNull();
    }

    @Test
    void manipulatingIdentifiersDoesNotBypassReviewAuthorization() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Target\",\"content\":\"body\"}");
        UUID reportId = createReport(studentToken, "DISCUSSION", discussionId);
        UUID unknownReportId = UUID.randomUUID();

        // A non-administrator learns nothing about which report identifiers exist.
        assertThat(patch("/admin/content-reports/" + unknownReportId, studentToken,
                "{\"status\":\"RESOLVED\"}").statusCode()).isEqualTo(403);
        assertThat(get("/admin/content-reports/" + unknownReportId, studentToken).statusCode()).isEqualTo(403);
        assertThat(patch("/admin/content-reports/" + reportId, studentToken,
                "{\"status\":\"RESOLVED\",\"reviewed_by\":\"" + adminId + "\"}").statusCode()).isEqualTo(403);
        assertThat(reportRow(reportId).get("status")).isEqualTo("PENDING");

        HttpResponse<String> missing = patch("/admin/content-reports/" + unknownReportId, adminToken,
                "{\"status\":\"RESOLVED\"}");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(code(missing)).isEqualTo("CONTENT_REPORT_NOT_FOUND");
        assertThat(reportCount()).isEqualTo(1L);
    }

    @Test
    void anUnresolvedReportBlocksDiscussionDeletionUntilTheOutcomeIsFinal() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Reported post\",\"content\":\"body\"}");
        String path = "/discussions/" + discussionId;
        UUID reportId = createReport(studentToken, "DISCUSSION", discussionId);

        for (String token : List.of(studentToken, tutorToken)) {
            HttpResponse<String> blocked = delete(path, token);
            assertThat(blocked.statusCode()).as("caller %s", token).isEqualTo(409);
            assertThat(code(blocked)).as("caller %s", token).isEqualTo("DISCUSSION_HAS_UNRESOLVED_REPORT");
        }
        assertThat(db.queryForObject("SELECT count(*) FROM discussion WHERE discussion_id = ?",
                Long.class, discussionId)).isEqualTo(1L);

        // REVIEWED is still awaiting a final outcome, so the post stays available.
        assertThat(patch("/admin/content-reports/" + reportId, adminToken,
                "{\"status\":\"REVIEWED\"}").statusCode()).isEqualTo(200);
        assertThat(code(delete(path, studentToken))).isEqualTo("DISCUSSION_HAS_UNRESOLVED_REPORT");
        assertThat(db.queryForObject("SELECT count(*) FROM discussion WHERE discussion_id = ?",
                Long.class, discussionId)).isEqualTo(1L);

        for (String outcome : List.of("RESOLVED", "DISMISSED")) {
            UUID releasable = createThread(studentToken, "{\"title\":\"" + outcome + "\",\"content\":\"body\"}");
            UUID outcomeReport = createReport(studentToken, "DISCUSSION", releasable);
            assertThat(patch("/admin/content-reports/" + outcomeReport, adminToken,
                    "{\"status\":\"" + outcome + "\"}").statusCode()).isEqualTo(200);
            assertThat(delete("/discussions/" + releasable, studentToken).statusCode()).isEqualTo(204);
            assertThat(db.queryForObject("SELECT count(*) FROM discussion WHERE discussion_id = ?",
                    Long.class, releasable)).isZero();
        }

        // A report on one post never blocks another post.
        UUID untouched = createThread(studentToken, "{\"title\":\"Untouched\",\"content\":\"body\"}");
        assertThat(delete("/discussions/" + untouched, studentToken).statusCode()).isEqualTo(204);
    }

    @Test
    void aRetainedReportTellsTheAdministratorItsTargetIsGone() throws Exception {
        UUID discussionId = createThread(studentToken, "{\"title\":\"Removed post\",\"content\":\"body\"}");
        UUID reportId = createReport(studentToken, "DISCUSSION", discussionId);
        assertThat(patch("/admin/content-reports/" + reportId, adminToken,
                "{\"status\":\"RESOLVED\"}").statusCode()).isEqualTo(200);
        assertThat(delete("/discussions/" + discussionId, studentToken).statusCode()).isEqualTo(204);

        HttpResponse<String> detail = get("/admin/content-reports/" + reportId, adminToken);
        assertThat(detail.statusCode()).isEqualTo(200);
        JsonNode body = json(detail);
        assertThat(body.get("target_available").asBoolean()).isFalse();
        assertThat(body.get("content_id").asString()).isEqualTo(discussionId.toString());
        assertThat(body.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(reportRow(reportId)).isNotEmpty();

        JsonNode listed = json(get("/admin/content-reports", adminToken));
        assertThat(listed.get("total_items").asLong()).isEqualTo(1L);
        assertThat(listed.get("items").get(0).get("target_available").asBoolean()).isFalse();
    }

    @Test
    void anUnresolvedReportBlocksMaterialDeletionUntilTheOutcomeIsFinal() throws Exception {
        UUID reportId = createReport(studentToken, "MATERIAL", materialId);
        String path = "/materials/" + materialId;

        HttpResponse<String> blocked = delete(path, tutorToken);
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(code(blocked)).isEqualTo("CONTENT_HAS_DEPENDENT_LEARNING_DATA");
        assertThat(db.queryForObject("SELECT count(*) FROM material WHERE material_id = ?",
                Long.class, materialId)).isEqualTo(1L);

        assertThat(patch("/admin/content-reports/" + reportId, adminToken,
                "{\"status\":\"DISMISSED\"}").statusCode()).isEqualTo(200);
        assertThat(delete(path, tutorToken).statusCode()).isEqualTo(204);
        assertThat(db.queryForObject("SELECT count(*) FROM material WHERE material_id = ?",
                Long.class, materialId)).isZero();
        assertThat(reportRow(reportId).get("status")).isEqualTo("DISMISSED");
        assertThat(json(get("/admin/content-reports/" + reportId, adminToken))
                .get("target_available").asBoolean()).isFalse();
    }

    private UUID createThread(String token, String body) throws Exception {
        return createThread(token, classId, body);
    }

    private UUID createThread(String token, UUID targetClassId, String body) throws Exception {
        HttpResponse<String> response = post("/classes/" + targetClassId + "/discussions", token, body);
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(json(response).get("discussion_id").asString());
    }

    private UUID createReport(String token, String contentType, UUID contentId) throws Exception {
        HttpResponse<String> response = post("/content-reports", token,
                reportBody(contentType, contentId, "SPAM", null));
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(json(response).get("report_id").asString());
    }

    private String reportBody(String contentType, UUID contentId, String reason, String description) {
        return "{\"content_type\":\"" + contentType + "\",\"content_id\":\"" + contentId
                + "\",\"reason\":\"" + reason + "\""
                + (description == null ? "" : ",\"description\":\"" + description + "\"") + "}";
    }

    private List<UUID> reportIds(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(UUID.fromString(item.get("report_id").asString())));
        return ids;
    }

    private Map<String, Object> reportRow(UUID reportId) {
        return db.queryForMap("SELECT * FROM content_report WHERE report_id = ?", reportId);
    }

    private long reportCount() {
        return db.queryForObject("SELECT count(*) FROM content_report", Long.class);
    }

    private String code(HttpResponse<String> response) {
        return json(response).get("code").asString();
    }

    private UUID insertUser(String role, String accessStatus) {
        UUID userId = UUID.randomUUID();
        userIds.add(userId);
        db.update("INSERT INTO lms_user_access(user_id, full_name, email, lms_role, access_status) "
                        + "VALUES (?, ?, ?, ?, ?)",
                userId, "Report " + userId, userId + "@example.test", role, accessStatus);
        return userId;
    }

    private UUID insertClass(UUID ownerTutorId) {
        UUID id = UUID.randomUUID();
        classIds.add(id);
        db.update("INSERT INTO class(class_id, tutor_id, class_code, title, enrollment_policy, status) "
                        + "VALUES (?, ?, ?, 'Report Test Class', 'OPEN', 'ACTIVE')",
                id, ownerTutorId, "R" + UUID.randomUUID().toString().substring(0, 8));
        return id;
    }

    private UUID insertChapter(UUID targetClassId) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO chapter(chapter_id, class_id, title, sequence_order, status) "
                + "VALUES (?, ?, 'Chapter', 0, 'PUBLISHED')", id, targetClassId);
        return id;
    }

    private UUID insertLesson(UUID chapterId) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO lesson(lesson_id, chapter_id, title, sequence_order, status) "
                + "VALUES (?, ?, 'Lesson', 0, 'PUBLISHED')", id, chapterId);
        return id;
    }

    private UUID insertMaterial(UUID targetLessonId, String title, String status) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO material(material_id, lesson_id, title, material_type, content, sequence_order, status) "
                + "VALUES (?, ?, ?, 'TEXT', 'body', 0, ?)", id, targetLessonId, title, status);
        return id;
    }

    private void insertMember(UUID targetClassId, UUID userId, String status) {
        db.update("INSERT INTO class_member(class_member_id, class_id, student_id, status, joined_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), targetClassId, userId, status);
    }

    private String tokenFor(UUID userId) throws Exception {
        var claims = JWT.claims();
        claims.put("sub", userId.toString());
        return JWT.token(claims);
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return request("POST", path, body, token);
    }

    private HttpResponse<String> patch(String path, String token, String body) throws Exception {
        return request("PATCH", path, body, token);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return request("GET", path, null, token);
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        return request("DELETE", path, null, token);
    }

    private HttpResponse<String> request(String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Accept", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) builder.header("Content-Type", "application/json");
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }
}