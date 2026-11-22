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

import java.math.BigDecimal;
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
 * LMS-14 basic operational reports over the canonical tables, exercised through real HTTP and PostgreSQL.
 * Unscoped (global) assertions compare before/after deltas so they remain valid on a shared test database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AdminReportPostgresHttpTest.Clocks.class)
class AdminReportPostgresHttpTest {

    private static final JwtTestSupport JWT = createJwtSupport();
    private static final String BASE = "/api/v1/admin/reports/";
    private static final String NO_DATA = "No reportable data is available.";
    private static final List<String> REPORTS = List.of("enrollment", "completion", "assessment");

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

    private UUID adminId;
    private UUID tutorId;
    private UUID studentId;
    /** ACTIVE class with every membership state, hidden content, quizzes and assignments. */
    private UUID classA;
    /** ARCHIVED class whose progress and assessment history must remain reportable. */
    private UUID classB;
    /** ACTIVE class with one ACTIVE member and no eligible Lessons. */
    private UUID classC;
    /** ACTIVE class with no reportable records at all. */
    private UUID classD;

    private Map<String, JsonNode> baseline;

    @BeforeEach
    void seed() throws Exception {
        adminId = user("ADMINISTRATOR");
        tutorId = user("TUTOR");
        studentId = user("STUDENT");
        baseline = Map.of(
                "enrollment", data(okReport("enrollment", null)),
                "completion", data(okReport("completion", null)),
                "assessment", data(okReport("assessment", null)));

        classA = newClass("ACTIVE");
        classB = newClass("ARCHIVED");
        classC = newClass("ACTIVE");
        classD = newClass("ACTIVE");

        // Class A memberships: 3 ACTIVE, 1 PENDING, 1 INACTIVE, 1 REJECTED.
        UUID a1 = member(classA, "ACTIVE");
        UUID a2 = member(classA, "ACTIVE");
        UUID a3 = member(classA, "ACTIVE");
        member(classA, "PENDING");
        UUID a5 = member(classA, "INACTIVE");
        member(classA, "REJECTED");

        // Class A content: only L1 and L2 are eligible (hidden lesson L3, published lesson L4 under hidden chapter).
        UUID chapterA1 = chapter(classA, "PUBLISHED", 0);
        UUID chapterA2 = chapter(classA, "HIDDEN", 1);
        UUID l1 = lesson(chapterA1, "PUBLISHED", 0);
        UUID l2 = lesson(chapterA1, "PUBLISHED", 1);
        UUID l3 = lesson(chapterA1, "HIDDEN", 2);
        UUID l4 = lesson(chapterA2, "PUBLISHED", 0);
        complete(a1, l1, l2, l3, l4);   // COMPLETED (2/2 eligible, 100%)
        complete(a2, l1);               // IN_PROGRESS (1/2, 50%)
        complete(a5, l1, l2);           // INACTIVE member: ignored

        // Class A assessments: multi-attempt quiz, valid 0.00 graded score, and ungraded records.
        UUID quizA = quiz(l1);
        attempt(quizA, a1, 1, "SUBMITTED", new BigDecimal("60.00"));
        attempt(quizA, a1, 2, "SUBMITTED", new BigDecimal("90.00"));
        attempt(quizA, a1, 3, "IN_PROGRESS", null);
        attempt(quizA, a2, 1, "SUBMITTED", null);             // submitted but ungraded
        attempt(quizA, a3, 1, "SUBMITTED", BigDecimal.ZERO);  // valid 0.00 graded score
        UUID assignmentA = assignment(l2);
        submission(assignmentA, a1, false, new BigDecimal("80.00"));
        submission(assignmentA, a2, true, new BigDecimal("71.25"));
        submission(assignmentA, a3, true, null);              // ungraded

        // Class B (ARCHIVED): 2 ACTIVE, 1 INACTIVE; 3 eligible lessons.
        UUID b1 = member(classB, "ACTIVE");
        UUID b2 = member(classB, "ACTIVE");
        member(classB, "INACTIVE");
        UUID chapterB = chapter(classB, "PUBLISHED", 0);
        UUID l5 = lesson(chapterB, "PUBLISHED", 0);
        UUID l6 = lesson(chapterB, "PUBLISHED", 1);
        UUID l7 = lesson(chapterB, "PUBLISHED", 2);
        complete(b1, l5, l6, l7);       // COMPLETED (100%)
        complete(b2, l5);               // IN_PROGRESS (33.33...%)
        UUID quizB = quiz(l5);
        attempt(quizB, b1, 1, "SUBMITTED", new BigDecimal("50.00"));

        // Class C: an ACTIVE member but zero eligible lessons -> must stay IN_PROGRESS.
        member(classC, "ACTIVE");
    }

    @AfterEach
    void cleanup() {
        for (UUID classId : classes) {
            db.update("DELETE FROM submission WHERE class_member_id IN "
                    + "(SELECT class_member_id FROM class_member WHERE class_id = ?)", classId);
            db.update("DELETE FROM quiz_attempt WHERE class_member_id IN "
                    + "(SELECT class_member_id FROM class_member WHERE class_id = ?)", classId);
            db.update("DELETE FROM completion_mark WHERE class_member_id IN "
                    + "(SELECT class_member_id FROM class_member WHERE class_id = ?)", classId);
            String activities = "SELECT a.activity_id FROM activity a JOIN lesson l ON l.lesson_id = a.lesson_id "
                    + "JOIN chapter ch ON ch.chapter_id = l.chapter_id WHERE ch.class_id = ?";
            db.update("DELETE FROM quiz WHERE activity_id IN (" + activities + ")", classId);
            db.update("DELETE FROM assignment WHERE activity_id IN (" + activities + ")", classId);
            db.update("DELETE FROM activity WHERE activity_id IN (" + activities + ")", classId);
            db.update("DELETE FROM lesson WHERE chapter_id IN (SELECT chapter_id FROM chapter WHERE class_id = ?)",
                    classId);
            db.update("DELETE FROM chapter WHERE class_id = ?", classId);
            db.update("DELETE FROM class_member WHERE class_id = ?", classId);
            db.update("DELETE FROM class WHERE class_id = ?", classId);
        }
        for (UUID userId : users) {
            db.update("DELETE FROM notification WHERE recipient_id = ?", userId);
            db.update("DELETE FROM lms_user_access WHERE user_id = ?", userId);
        }
    }

    // ---------------------------------------------------------------- empty data

    @Test
    void scopedEmptyClassReturnsOkWithZeroCountsMessageAndNullAverages() throws Exception {
        JsonNode enrollment = okReport("enrollment", classD);
        assertThat(enrollment.get("message").asText()).isEqualTo(NO_DATA);
        assertCounts(enrollment.get("data"), Map.of("active_members", 0L, "pending_requests", 0L,
                "inactive_members", 0L, "rejected_requests", 0L, "total_memberships", 0L,
                "total_classes", 1L, "active_classes", 1L, "archived_classes", 0L));

        JsonNode completion = okReport("completion", classD);
        assertThat(completion.get("message").asText()).isEqualTo(NO_DATA);
        assertCounts(completion.get("data"), Map.of("active_members", 0L, "eligible_lessons", 0L,
                "completed_lesson_marks", 0L, "completed_memberships", 0L, "in_progress_memberships", 0L));
        assertDecimal(completion.get("data"), "completion_rate", "0.00");
        assertDecimal(completion.get("data"), "average_progress_percentage", "0.00");

        JsonNode assessment = okReport("assessment", classD);
        assertThat(assessment.get("message").asText()).isEqualTo(NO_DATA);
        assertCounts(assessment.get("data"), Map.of("total_quiz_attempts", 0L, "in_progress_quiz_attempts", 0L,
                "submitted_quiz_attempts", 0L, "graded_quiz_attempts", 0L, "total_assignment_submissions", 0L,
                "ungraded_assignment_submissions", 0L, "graded_assignment_submissions", 0L,
                "late_assignment_submissions", 0L));
        assertNullField(assessment.get("data"), "average_quiz_attempt_score");
        assertNullField(assessment.get("data"), "average_assignment_submission_score");
        assertNullField(assessment.get("data"), "average_assessment_score");
    }

    @Test
    void responseEnvelopeEchoesFiltersAndOmitsMessageWhenDataExists() throws Exception {
        for (String report : REPORTS) {
            JsonNode scoped = okReport(report, classA);
            assertThat(scoped.get("report_type").asText()).isEqualTo(report);
            assertThat(scoped.get("filters").size()).isEqualTo(1);
            assertThat(scoped.get("filters").get("class_id").asText()).isEqualTo(classA.toString());
            assertThat(scoped.has("message")).as(report).isFalse();
            assertThat(scoped.get("data").isObject()).isTrue();

            JsonNode global = okReport(report, null);
            assertThat(global.get("report_type").asText()).isEqualTo(report);
            assertThat(global.get("filters").isObject()).isTrue();
            assertThat(global.get("filters").size()).isZero();
            assertThat(global.has("message")).as(report).isFalse();
        }
    }

    // ---------------------------------------------------------------- enrollment

    @Test
    void enrollmentReportCountsEveryMembershipStateWithinClassScope() throws Exception {
        JsonNode a = okReport("enrollment", classA).get("data");
        assertCounts(a, Map.of("active_members", 3L, "pending_requests", 1L, "inactive_members", 1L,
                "rejected_requests", 1L, "total_memberships", 6L, "total_classes", 1L,
                "active_classes", 1L, "archived_classes", 0L));

        JsonNode b = okReport("enrollment", classB).get("data");
        assertCounts(b, Map.of("active_members", 2L, "pending_requests", 0L, "inactive_members", 1L,
                "rejected_requests", 0L, "total_memberships", 3L, "total_classes", 1L,
                "active_classes", 0L, "archived_classes", 1L));
    }

    @Test
    void globalEnrollmentReportIncludesArchivedClassesAndAllStates() throws Exception {
        JsonNode after = okReport("enrollment", null).get("data");
        assertDeltas(baseline.get("enrollment"), after, Map.of("active_members", 6L, "pending_requests", 1L,
                "inactive_members", 2L, "rejected_requests", 1L, "total_memberships", 10L,
                "total_classes", 4L, "active_classes", 3L, "archived_classes", 1L));
    }

    // ---------------------------------------------------------------- completion

    @Test
    void completionReportUsesActiveMembersAndEligiblePublishedLessonsOnly() throws Exception {
        JsonNode a = okReport("completion", classA).get("data");
        assertCounts(a, Map.of("active_members", 3L, "eligible_lessons", 2L, "completed_lesson_marks", 3L,
                "completed_memberships", 1L, "in_progress_memberships", 2L));
        assertDecimal(a, "completion_rate", "33.33");
        assertDecimal(a, "average_progress_percentage", "50.00");
    }

    @Test
    void completionReportKeepsArchivedClassHistoryAndRoundsHalfUp() throws Exception {
        JsonNode b = okReport("completion", classB).get("data");
        assertCounts(b, Map.of("active_members", 2L, "eligible_lessons", 3L, "completed_lesson_marks", 4L,
                "completed_memberships", 1L, "in_progress_memberships", 1L));
        assertDecimal(b, "completion_rate", "50.00");
        // (100 + 33.333...) / 2 = 66.666... -> 66.67
        assertDecimal(b, "average_progress_percentage", "66.67");
    }

    @Test
    void classWithoutEligibleLessonsNeverReportsCompletedMemberships() throws Exception {
        JsonNode response = okReport("completion", classC);
        assertThat(response.has("message")).isFalse();
        JsonNode c = response.get("data");
        assertCounts(c, Map.of("active_members", 1L, "eligible_lessons", 0L, "completed_lesson_marks", 0L,
                "completed_memberships", 0L, "in_progress_memberships", 1L));
        assertDecimal(c, "completion_rate", "0.00");
        assertDecimal(c, "average_progress_percentage", "0.00");
    }

    @Test
    void globalCompletionReportAggregatesAcrossClasses() throws Exception {
        JsonNode after = okReport("completion", null).get("data");
        assertDeltas(baseline.get("completion"), after, Map.of("active_members", 6L, "eligible_lessons", 5L,
                "completed_lesson_marks", 7L, "completed_memberships", 2L, "in_progress_memberships", 4L));
    }

    // ---------------------------------------------------------------- assessment

    @Test
    void assessmentReportUsesAttemptAndSubmissionLevelStatistics() throws Exception {
        JsonNode a = okReport("assessment", classA).get("data");
        // Three students produced five attempts: attempts are counted, not distinct students.
        // Valid 0.00 score (a3) counts as graded; IN_PROGRESS and ungraded SUBMITTED are excluded.
        assertCounts(a, Map.of("total_quiz_attempts", 5L, "in_progress_quiz_attempts", 1L,
                "submitted_quiz_attempts", 4L, "graded_quiz_attempts", 3L,
                "total_assignment_submissions", 3L, "ungraded_assignment_submissions", 1L,
                "graded_assignment_submissions", 2L, "late_assignment_submissions", 2L));
        // (60 + 90 + 0) / 3 = 50.00 (proves 0.00 is included; excluding it would yield 75.00).
        assertDecimal(a, "average_quiz_attempt_score", "50.00");
        // (80 + 71.25) / 2 = 75.625 -> 75.63
        assertDecimal(a, "average_assignment_submission_score", "75.63");
        // Weighted across all 5 graded records: (60 + 90 + 0 + 80 + 71.25) / 5 = 60.25
        // (unweighted average of category averages would be (50.00 + 75.625) / 2 = 62.81).
        assertDecimal(a, "average_assessment_score", "60.25");
    }

    @Test
    void assessmentAverageIsNullWhenNoGradedRecordsExistForThatKind() throws Exception {
        JsonNode response = okReport("assessment", classB);
        assertThat(response.has("message")).isFalse();
        JsonNode b = response.get("data");
        assertCounts(b, Map.of("total_quiz_attempts", 1L, "graded_quiz_attempts", 1L,
                "total_assignment_submissions", 0L, "graded_assignment_submissions", 0L));
        assertDecimal(b, "average_quiz_attempt_score", "50.00");
        assertNullField(b, "average_assignment_submission_score");
        assertDecimal(b, "average_assessment_score", "50.00");
    }

    @Test
    void zeroScoreAssignmentSubmissionCountsAsGradedAndContributesToWeightedAverage() throws Exception {
        UUID zeroClass = newClass("ACTIVE");
        UUID m1 = member(zeroClass, "ACTIVE");
        UUID m2 = member(zeroClass, "ACTIVE");
        UUID ch = chapter(zeroClass, "PUBLISHED", 0);
        UUID l = lesson(ch, "PUBLISHED", 0);
        UUID q = quiz(l);
        attempt(q, m1, 1, "SUBMITTED", new BigDecimal("100.00"));
        attempt(q, m2, 1, "SUBMITTED", new BigDecimal("100.00"));
        UUID asg = assignment(l);
        submission(asg, m1, false, BigDecimal.ZERO);   // valid graded 0.00
        submission(asg, m2, false, null);              // ungraded

        JsonNode data = okReport("assessment", zeroClass).get("data");
        assertCounts(data, Map.of("total_quiz_attempts", 2L, "graded_quiz_attempts", 2L,
                "total_assignment_submissions", 2L, "ungraded_assignment_submissions", 1L,
                "graded_assignment_submissions", 1L));
        assertDecimal(data, "average_quiz_attempt_score", "100.00");
        assertDecimal(data, "average_assignment_submission_score", "0.00");
        // Weighted: (100 + 100 + 0) / 3 = 66.666... -> 66.67 (unweighted would be (100 + 0) / 2 = 50.00).
        assertDecimal(data, "average_assessment_score", "66.67");
    }

    @Test
    void globalAssessmentReportAggregatesAcrossClasses() throws Exception {
        JsonNode after = okReport("assessment", null).get("data");
        assertDeltas(baseline.get("assessment"), after, Map.of("total_quiz_attempts", 6L,
                "in_progress_quiz_attempts", 1L, "submitted_quiz_attempts", 5L, "graded_quiz_attempts", 4L,
                "total_assignment_submissions", 3L, "ungraded_assignment_submissions", 1L,
                "graded_assignment_submissions", 2L, "late_assignment_submissions", 2L));
    }

    // ---------------------------------------------------------------- validation

    @Test
    void invalidQueryParametersReturnValidationError() throws Exception {
        List<String> queries = List.of("class_id=not-a-uuid", "class_id=", "class_id=%20",
                "class_id=1-1-1-1-1", "class_id=" + classA + "&class_id=" + classB,
                "from=2026-01-01", "to=2026-12-31", "foo=bar", "class_id=" + classA + "&from=2026-01-01");
        for (String report : REPORTS) {
            for (String query : queries) {
                assertError(get(BASE + report + "?" + query, adminId), 400, "VALIDATION_ERROR");
            }
        }
    }

    @Test
    void nonExistentClassReturnsClassNotFound() throws Exception {
        for (String report : REPORTS) {
            assertError(get(BASE + report + "?class_id=" + UUID.randomUUID(), adminId), 404, "CLASS_NOT_FOUND");
        }
    }

    // ---------------------------------------------------------------- authorization

    @Test
    void onlyActiveAdministratorsCanReadReports() throws Exception {
        for (String report : REPORTS) {
            assertError(get(BASE + report, null), 401, "UNAUTHORIZED");
            assertError(get(BASE + report, studentId), 403, "FORBIDDEN");
            assertError(get(BASE + report, tutorId), 403, "FORBIDDEN");
            assertThat(get(BASE + report, adminId).statusCode()).isEqualTo(200);
        }
        db.update("UPDATE lms_user_access SET access_status = 'SUSPENDED' WHERE user_id = ?", adminId);
        for (String report : REPORTS) {
            assertError(get(BASE + report, adminId), 403, "FORBIDDEN");
        }
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode okReport(String report, UUID classId) throws Exception {
        String path = BASE + report + (classId == null ? "" : "?class_id=" + classId);
        HttpResponse<String> response = get(path, adminId);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private static JsonNode data(JsonNode report) {
        return report.get("data");
    }

    private HttpResponse<String> get(String path, UUID userId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (userId != null) {
            var claims = JWT.claims();
            claims.put("sub", userId.toString());
            builder.header("Authorization", "Bearer " + JWT.token(claims));
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode json = objectMapper.readTree(response.body());
        assertThat(json.get("code").asText()).isEqualTo(code);
        assertThat(json.get("details").isArray()).isTrue();
    }

    private static void assertCounts(JsonNode data, Map<String, Long> expected) {
        expected.forEach((field, value) -> {
            assertThat(data.has(field)).as(field).isTrue();
            assertThat(data.get(field).asLong()).as(field).isEqualTo(value);
        });
    }

    private static void assertDeltas(JsonNode before, JsonNode after, Map<String, Long> expected) {
        expected.forEach((field, value) ->
                assertThat(after.get(field).asLong() - before.get(field).asLong()).as(field).isEqualTo(value));
    }

    private static void assertDecimal(JsonNode data, String field, String expected) {
        assertThat(data.has(field)).as(field).isTrue();
        assertThat(data.get(field).isNumber()).as(field).isTrue();
        assertThat(data.get(field).decimalValue()).as(field).isEqualByComparingTo(expected);
    }

    private static void assertNullField(JsonNode data, String field) {
        assertThat(data.has(field)).as(field + " must be present").isTrue();
        assertThat(data.get(field).isNull()).as(field).isTrue();
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        users.add(id);
        db.update("INSERT INTO lms_user_access(user_id, full_name, email, lms_role, access_status) "
                + "VALUES (?, ?, ?, ?, 'ACTIVE')", id, "Report " + role, id + "@example.test", role);
        return id;
    }

    private UUID newClass(String status) {
        UUID id = UUID.randomUUID();
        classes.add(id);
        db.update("INSERT INTO class(class_id, tutor_id, class_code, title, enrollment_policy, status) "
                        + "VALUES (?, ?, ?, 'Report Class', 'APPROVAL_REQUIRED', ?)",
                id, tutorId, "R" + id.toString().substring(0, 8), status);
        return id;
    }

    private UUID member(UUID classId, String status) {
        UUID memberId = UUID.randomUUID();
        UUID student = user("STUDENT");
        db.update("INSERT INTO class_member(class_member_id, class_id, student_id, status, requested_at, joined_at) "
                        + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, "
                        + "CASE WHEN ? IN ('ACTIVE', 'INACTIVE') THEN CURRENT_TIMESTAMP END)",
                memberId, classId, student, status, status);
        return memberId;
    }

    private UUID chapter(UUID classId, String status, int order) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO chapter(chapter_id, class_id, title, sequence_order, status) VALUES (?, ?, 'Ch', ?, ?)",
                id, classId, order, status);
        return id;
    }

    private UUID lesson(UUID chapterId, String status, int order) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO lesson(lesson_id, chapter_id, title, sequence_order, status) VALUES (?, ?, 'L', ?, ?)",
                id, chapterId, order, status);
        return id;
    }

    private void complete(UUID memberId, UUID... lessons) {
        for (UUID lessonId : lessons) {
            db.update("INSERT INTO completion_mark(mark_id, class_member_id, lesson_id) VALUES (?, ?, ?)",
                    UUID.randomUUID(), memberId, lessonId);
        }
    }

    private UUID activity(UUID lessonId, String type) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO activity(activity_id, lesson_id, title, activity_type, sequence_order, status) "
                + "VALUES (?, ?, 'Assessment', ?, 0, 'PUBLISHED')", id, lessonId, type);
        return id;
    }

    private UUID quiz(UUID lessonId) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO quiz(quiz_id, activity_id, time_limit_minutes, attempts_allowed, deadline) "
                + "VALUES (?, ?, 30, 3, TIMESTAMPTZ '2031-01-01 00:00:00+00')", id, activity(lessonId, "QUIZ"));
        return id;
    }

    private void attempt(UUID quizId, UUID memberId, int number, String status, BigDecimal score) {
        boolean submitted = "SUBMITTED".equals(status);
        db.update("INSERT INTO quiz_attempt(attempt_id, quiz_id, class_member_id, attempt_number, status, "
                        + "submitted_at, score, graded_at) VALUES (?, ?, ?, ?, ?, "
                        + "CASE WHEN ? THEN CURRENT_TIMESTAMP END, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP END)",
                UUID.randomUUID(), quizId, memberId, number, status, submitted, score, score != null);
    }

    private UUID assignment(UUID lessonId) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO assignment(assignment_id, activity_id, deadline, allow_late) "
                        + "VALUES (?, ?, TIMESTAMPTZ '2031-01-01 00:00:00+00', TRUE)",
                id, activity(lessonId, "ASSIGNMENT"));
        return id;
    }

    private void submission(UUID assignmentId, UUID memberId, boolean late, BigDecimal score) {
        boolean graded = score != null;
        db.update("INSERT INTO submission(submission_id, assignment_id, class_member_id, file_name, storage_path, "
                        + "mime_type, size_bytes, is_late, status, score, graded_by, graded_at) "
                        + "VALUES (?, ?, ?, 'work.pdf', 'submissions/work.pdf', 'application/pdf', 10, ?, ?, ?, ?, "
                        + "CASE WHEN ? THEN CURRENT_TIMESTAMP END)",
                UUID.randomUUID(), assignmentId, memberId, late, graded ? "GRADED" : "SUBMITTED", score,
                graded ? tutorId : null, graded);
    }
}
