package vn.edu.hcmut.co3103.lms.controller.progress;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.edu.hcmut.co3103.lms.dto.request.QuestionCreateRequest;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;
import vn.edu.hcmut.co3103.lms.service.assessment.QuizCreation;
import vn.edu.hcmut.co3103.lms.service.assessment.QuizCreationService;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LearningProgressPostgresHttpTest.Clocks.class)
class LearningProgressPostgresHttpTest {

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
        registry.add("lms.quiz.expiry-scan-ms", () -> "3600000");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate db;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private QuizCreationService quizCreationService;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();

    private UUID tutorId;
    private UUID otherTutorId;
    private UUID activeStudentId;
    private UUID secondStudentId;
    private UUID classId;
    private UUID activeMemberId;
    private UUID chapterId;
    private UUID lesson1Id;
    private UUID lesson2Id;
    private String tutorToken;
    private String otherTutorToken;
    private String activeStudentToken;
    private String secondStudentToken;

    @BeforeEach
    void seed() throws Exception {
        tutorId = UUID.randomUUID();
        otherTutorId = UUID.randomUUID();
        activeStudentId = UUID.randomUUID();
        secondStudentId = UUID.randomUUID();
        classId = UUID.randomUUID();
        activeMemberId = UUID.randomUUID();
        chapterId = UUID.randomUUID();
        lesson1Id = UUID.randomUUID();
        lesson2Id = UUID.randomUUID();

        insertUser(tutorId, "TUTOR");
        insertUser(otherTutorId, "TUTOR");
        insertUser(activeStudentId, "STUDENT");
        insertUser(secondStudentId, "STUDENT");

        db.update(
                "INSERT INTO class(class_id, tutor_id, class_code, title, enrollment_policy, status) "
                        + "VALUES (?, ?, ?, 'Progress Test Class', 'APPROVAL_REQUIRED', 'ACTIVE')",
                classId, tutorId, "P" + UUID.randomUUID().toString().substring(0, 8)
        );
        db.update(
                "INSERT INTO class_member(class_member_id, class_id, student_id, status, joined_at) "
                        + "VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)",
                activeMemberId, classId, activeStudentId
        );
        db.update(
                "INSERT INTO chapter(chapter_id, class_id, title, sequence_order, status) "
                        + "VALUES (?, ?, 'Chapter 1', 0, 'PUBLISHED')",
                chapterId, classId
        );
        db.update(
                "INSERT INTO lesson(lesson_id, chapter_id, title, sequence_order, status) "
                        + "VALUES (?, ?, 'Lesson 1', 0, 'PUBLISHED')",
                lesson1Id, chapterId
        );
        db.update(
                "INSERT INTO lesson(lesson_id, chapter_id, title, sequence_order, status) "
                        + "VALUES (?, ?, 'Lesson 2', 1, 'PUBLISHED')",
                lesson2Id, chapterId
        );

        tutorToken = tokenFor(tutorId);
        otherTutorToken = tokenFor(otherTutorId);
        activeStudentToken = tokenFor(activeStudentId);
        secondStudentToken = tokenFor(secondStudentId);
    }

    @AfterEach
    void cleanup() {
        db.update("DELETE FROM attempt_answer_option WHERE answer_id IN "
                + "(SELECT answer_id FROM attempt_answer WHERE attempt_id IN "
                + "(SELECT attempt_id FROM quiz_attempt WHERE class_member_id IN "
                + "(SELECT class_member_id FROM class_member WHERE class_id = ?)))", classId);
        db.update("DELETE FROM attempt_answer WHERE attempt_id IN "
                + "(SELECT attempt_id FROM quiz_attempt WHERE class_member_id IN "
                + "(SELECT class_member_id FROM class_member WHERE class_id = ?))", classId);
        db.update("DELETE FROM quiz_attempt WHERE class_member_id IN "
                + "(SELECT class_member_id FROM class_member WHERE class_id = ?)", classId);
        db.update("DELETE FROM question_option WHERE question_id IN "
                + "(SELECT question_id FROM question WHERE quiz_id IN "
                + "(SELECT quiz_id FROM quiz WHERE activity_id IN "
                + "(SELECT activity_id FROM activity WHERE lesson_id IN "
                + "(SELECT lesson_id FROM lesson WHERE chapter_id = ?))))", chapterId);
        db.update("DELETE FROM question WHERE quiz_id IN "
                + "(SELECT quiz_id FROM quiz WHERE activity_id IN "
                + "(SELECT activity_id FROM activity WHERE lesson_id IN "
                + "(SELECT lesson_id FROM lesson WHERE chapter_id = ?)))", chapterId);
        db.update("DELETE FROM quiz WHERE activity_id IN "
                + "(SELECT activity_id FROM activity WHERE lesson_id IN "
                + "(SELECT lesson_id FROM lesson WHERE chapter_id = ?))", chapterId);
        db.update("DELETE FROM activity WHERE lesson_id IN "
                + "(SELECT lesson_id FROM lesson WHERE chapter_id = ?)", chapterId);
        db.update("DELETE FROM completion_mark WHERE class_member_id IN "
                + "(SELECT class_member_id FROM class_member WHERE class_id = ?)", classId);
        db.update("DELETE FROM last_visit WHERE class_member_id IN "
                + "(SELECT class_member_id FROM class_member WHERE class_id = ?)", classId);
        db.update("DELETE FROM lesson WHERE chapter_id = ?", chapterId);
        db.update("DELETE FROM chapter WHERE class_id = ?", classId);
        db.update("DELETE FROM class_member WHERE class_id = ?", classId);
        db.update("DELETE FROM class WHERE class_id = ?", classId);
        db.update("DELETE FROM lms_user_access WHERE user_id IN (?, ?, ?, ?)",
                tutorId, otherTutorId, activeStudentId, secondStudentId);
    }

    @Test
    void lessonWithCompletionMarkCannotBeDeletedWhileLessonWithOnlyLastVisitCanBeDeleted() throws Exception {
        // Mark lesson1 complete -> creates completion_mark row
        HttpResponse<String> markRes = request(
                "PUT", "/classes/" + classId + "/lessons/" + lesson1Id + "/completion", null, activeStudentToken
        );
        assertThat(markRes.statusCode()).isEqualTo(200);

        // Tutor attempts physical delete of lesson1 -> must fail with 409 CONTENT_HAS_DEPENDENT_LEARNING_DATA
        HttpResponse<String> deleteLesson1 = request("DELETE", "/lessons/" + lesson1Id, null, tutorToken);
        assertThat(deleteLesson1.statusCode()).isEqualTo(409);
        JsonNode errorJson = objectMapper.readTree(deleteLesson1.body());
        assertThat(errorJson.get("code").asText()).isEqualTo("CONTENT_HAS_DEPENDENT_LEARNING_DATA");

        // Verify neither lesson1 nor its completion_mark was deleted
        Integer lesson1Count = db.queryForObject(
                "SELECT COUNT(*) FROM lesson WHERE lesson_id = ?", Integer.class, lesson1Id
        );
        Integer markCount = db.queryForObject(
                "SELECT COUNT(*) FROM completion_mark WHERE class_member_id = ? AND lesson_id = ?",
                Integer.class, activeMemberId, lesson1Id
        );
        assertThat(lesson1Count).isEqualTo(1);
        assertThat(markCount).isEqualTo(1);

        // Record last visit on lesson2 (no completion mark on lesson2)
        HttpResponse<String> visitRes = request(
                "PUT",
                "/classes/" + classId + "/last-visit",
                "{\"lesson_id\":\"" + lesson2Id + "\"}",
                activeStudentToken
        );
        assertThat(visitRes.statusCode()).isEqualTo(200);

        // Tutor deletes lesson2 -> succeeds (204) and cascades deletion of last_visit
        HttpResponse<String> deleteLesson2 = request("DELETE", "/lessons/" + lesson2Id, null, tutorToken);
        assertThat(deleteLesson2.statusCode()).isEqualTo(204);
        Integer visitCount = db.queryForObject(
                "SELECT COUNT(*) FROM last_visit WHERE class_member_id = ?", Integer.class, activeMemberId
        );
        assertThat(visitCount).isZero();
    }

    @Test
    void withdrawnJoinRequestBecomesInactiveAndCannotAccessClassProgressResumeOrMyClasses() throws Exception {
        // secondStudent requests to join APPROVAL_REQUIRED class -> PENDING
        HttpResponse<String> joinReq = request("POST", "/classes/" + classId + "/join-requests", null, secondStudentToken);
        assertThat(joinReq.statusCode()).isEqualTo(201);

        // While PENDING, /me/classes lists the pending request with null progress, but /progress is forbidden
        HttpResponse<String> myClassesPending = request("GET", "/me/classes", null, secondStudentToken);
        assertThat(myClassesPending.statusCode()).isEqualTo(200);
        JsonNode pendingItems = objectMapper.readTree(myClassesPending.body()).get("items");
        assertThat(pendingItems.size()).isEqualTo(1);
        assertThat(pendingItems.get(0).get("membership_status").asText()).isEqualTo("PENDING");
        assertThat(pendingItems.get(0).get("progress").isNull()).isTrue();

        assertThat(request("GET", "/classes/" + classId + "/progress", null, secondStudentToken).statusCode())
                .isEqualTo(403);

        // secondStudent withdraws join request -> becomes INACTIVE in DB
        HttpResponse<String> withdrawRes = request(
                "DELETE", "/classes/" + classId + "/membership-request", null, secondStudentToken
        );
        assertThat(withdrawRes.statusCode()).isEqualTo(204);

        UUID inactiveMemberId = db.queryForObject(
                "SELECT class_member_id FROM class_member WHERE class_id = ? AND student_id = ?",
                UUID.class, classId, secondStudentId
        );

        // INACTIVE user must NOT see the class in /me/classes and must be rejected from /progress and /resume-target
        HttpResponse<String> myClassesAfterWithdraw = request("GET", "/me/classes", null, secondStudentToken);
        assertThat(myClassesAfterWithdraw.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(myClassesAfterWithdraw.body()).get("items").size()).isZero();

        HttpResponse<String> progressInactive = request(
                "GET", "/classes/" + classId + "/progress", null, secondStudentToken
        );
        assertThat(progressInactive.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(progressInactive.body()).get("code").asText())
                .isEqualTo("MEMBERSHIP_REQUIRED");

        HttpResponse<String> resumeInactive = request(
                "GET", "/classes/" + classId + "/resume-target", null, secondStudentToken
        );
        assertThat(resumeInactive.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(resumeInactive.body()).get("code").asText())
                .isEqualTo("MEMBERSHIP_REQUIRED");

        // Tutor must also receive 404 CLASS_OR_MEMBER_NOT_FOUND for INACTIVE member progress
        HttpResponse<String> tutorInactiveMember = request(
                "GET", "/tutor/classes/" + classId + "/members/" + inactiveMemberId + "/progress", null, tutorToken
        );
        assertThat(tutorInactiveMember.statusCode()).isEqualTo(404);
        assertThat(objectMapper.readTree(tutorInactiveMember.body()).get("code").asText())
                .isEqualTo("CLASS_OR_MEMBER_NOT_FOUND");
    }

    @Test
    void endToEndCompletionIdempotencyResumeConsistencyAssessmentHistoryAndTutorProgress() throws Exception {
        // Create a Quiz on lesson1
        UUID quizId = new TransactionTemplate(transactionManager).execute(status -> {
            Activity activity = activityRepository.saveAndFlush(new Activity(
                    lesson1Id, "Lesson 1 Quiz", null, Activity.ActivityType.QUIZ, 0, Chapter.VisibilityStatus.PUBLISHED
            ));
            return quizCreationService.createForActivity(
                    activity.getActivityId(),
                    tutorId,
                    new QuizCreation(
                            30, 2, "HIGHEST", null, OffsetDateTime.now().plusDays(1), null,
                            List.of(new QuestionCreateRequest(
                                    "MCQ_SINGLE", "2 + 2 = ?", BigDecimal.TEN, BigDecimal.ZERO, 0,
                                    List.of(
                                            new QuestionCreateRequest.Option("4", true, 0),
                                            new QuestionCreateRequest.Option("5", false, 1)
                                    ),
                                    null
                            ))
                    )
            ).quizId();
        });

        // 1. Student starts a quiz attempt -> resume target in both /progress and /resume-target is QUIZ_ATTEMPT
        HttpResponse<String> startAttempt = request("POST", "/quizzes/" + quizId + "/attempts", null, activeStudentToken);
        assertThat(startAttempt.statusCode()).isEqualTo(201);
        UUID attemptId = UUID.fromString(objectMapper.readTree(startAttempt.body()).get("attempt_id").asText());

        JsonNode progressWithQuiz = objectMapper.readTree(
                request("GET", "/classes/" + classId + "/progress", null, activeStudentToken).body()
        );
        JsonNode resumeWithQuiz = objectMapper.readTree(
                request("GET", "/classes/" + classId + "/resume-target", null, activeStudentToken).body()
        );
        assertThat(resumeWithQuiz.get("target_type").asText()).isEqualTo("QUIZ_ATTEMPT");
        assertThat(resumeWithQuiz.get("target_id").asText()).isEqualTo(attemptId.toString());
        assertThat(progressWithQuiz.get("resume_target")).isEqualTo(resumeWithQuiz);

        // 2. Submit quiz attempt -> no longer in progress
        HttpResponse<String> submitRes = request("POST", "/quiz-attempts/" + attemptId + "/submit", null, activeStudentToken);
        assertThat(submitRes.statusCode()).isEqualTo(200);

        // 3. Record last visit to lesson2 -> resume target becomes lesson2 (MOST_RECENTLY_VISITED_LESSON)
        assertThat(request(
                "PUT",
                "/classes/" + classId + "/last-visit",
                "{\"lesson_id\":\"" + lesson2Id + "\"}",
                activeStudentToken
        ).statusCode()).isEqualTo(200);

        JsonNode resumeVisited = objectMapper.readTree(
                request("GET", "/classes/" + classId + "/resume-target", null, activeStudentToken).body()
        );
        assertThat(resumeVisited.get("target_type").asText()).isEqualTo("LESSON");
        assertThat(resumeVisited.get("target_id").asText()).isEqualTo(lesson2Id.toString());
        assertThat(resumeVisited.get("reason").asText()).isEqualTo("MOST_RECENTLY_VISITED_LESSON");

        // 4. Mark lesson2 complete and verify repeated PUT preserves original marked_at
        HttpResponse<String> mark1 = request(
                "PUT", "/classes/" + classId + "/lessons/" + lesson2Id + "/completion", null, activeStudentToken
        );
        assertThat(mark1.statusCode()).isEqualTo(200);
        String originalMarkedAt = objectMapper.readTree(mark1.body()).get("marked_at").asText();

        Thread.sleep(15);
        HttpResponse<String> mark2 = request(
                "PUT", "/classes/" + classId + "/lessons/" + lesson2Id + "/completion", null, activeStudentToken
        );
        assertThat(mark2.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(mark2.body()).get("marked_at").asText()).isEqualTo(originalMarkedAt);

        // Since lesson2 (the LastVisit lesson) is now completed, resume target must skip lesson2 and select lesson1!
        JsonNode resumeAfterLesson2Complete = objectMapper.readTree(
                request("GET", "/classes/" + classId + "/resume-target", null, activeStudentToken).body()
        );
        assertThat(resumeAfterLesson2Complete.get("target_type").asText()).isEqualTo("LESSON");
        assertThat(resumeAfterLesson2Complete.get("target_id").asText()).isEqualTo(lesson1Id.toString());
        assertThat(resumeAfterLesson2Complete.get("reason").asText()).isEqualTo("FIRST_INCOMPLETE_LESSON");

        // 5. Mark lesson1 complete -> all lessons completed -> CLASS_OUTLINE (NO_UNFINISHED_TARGET)
        assertThat(request(
                "PUT", "/classes/" + classId + "/lessons/" + lesson1Id + "/completion", null, activeStudentToken
        ).statusCode()).isEqualTo(200);

        JsonNode progressAllDone = objectMapper.readTree(
                request("GET", "/classes/" + classId + "/progress", null, activeStudentToken).body()
        );
        JsonNode resumeAllDone = objectMapper.readTree(
                request("GET", "/classes/" + classId + "/resume-target", null, activeStudentToken).body()
        );
        assertThat(progressAllDone.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(progressAllDone.get("progress_percentage").asInt()).isEqualTo(100);
        assertThat(resumeAllDone.get("target_type").asText()).isEqualTo("CLASS_OUTLINE");
        assertThat(resumeAllDone.get("reason").asText()).isEqualTo("NO_UNFINISHED_TARGET");
        assertThat(progressAllDone.get("resume_target")).isEqualTo(resumeAllDone);

        // 6. Student assessment history returns the submitted attempt, and secondStudent sees an empty history
        HttpResponse<String> myHistory = request(
                "GET", "/me/assessment-history?class_id=" + classId + "&type=QUIZ", null, activeStudentToken
        );
        assertThat(myHistory.statusCode()).isEqualTo(200);
        JsonNode historyItems = objectMapper.readTree(myHistory.body()).get("items");
        assertThat(historyItems.size()).isEqualTo(1);
        assertThat(historyItems.get(0).get("attempt_id").asText()).isEqualTo(attemptId.toString());
        assertThat(historyItems.get(0).get("status").asText()).isEqualTo("SUBMITTED");

        HttpResponse<String> otherStudentHistory = request("GET", "/me/assessment-history", null, secondStudentToken);
        assertThat(otherStudentHistory.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(otherStudentHistory.body()).get("items").size()).isZero();

        // 7. Tutor member progress includes lesson assessment_statuses and assessment_outcomes; non-owner gets 403
        assertThat(request("GET", "/tutor/classes/" + classId + "/progress", null, otherTutorToken).statusCode())
                .isEqualTo(403);

        HttpResponse<String> tutorMemberRes = request(
                "GET", "/tutor/classes/" + classId + "/members/" + activeMemberId + "/progress", null, tutorToken
        );
        assertThat(tutorMemberRes.statusCode()).isEqualTo(200);
        JsonNode tutorMemberJson = objectMapper.readTree(tutorMemberRes.body());
        assertThat(tutorMemberJson.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(tutorMemberJson.get("chapters").get(0).get("lessons").get(0)
                .get("assessment_statuses").get(0).get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(tutorMemberJson.get("assessment_outcomes").size()).isEqualTo(1);

        // 8. Archive class -> ACTIVE student can still read progress/resume, but cannot write completion or last-visit
        db.update("UPDATE class SET status = 'ARCHIVED' WHERE class_id = ?", classId);
        assertThat(request("GET", "/classes/" + classId + "/progress", null, activeStudentToken).statusCode())
                .isEqualTo(200);
        assertThat(request("GET", "/classes/" + classId + "/resume-target", null, activeStudentToken).statusCode())
                .isEqualTo(200);
        assertThat(request(
                "DELETE", "/classes/" + classId + "/lessons/" + lesson1Id + "/completion", null, activeStudentToken
        ).statusCode()).isEqualTo(409);
        assertThat(request(
                "PUT",
                "/classes/" + classId + "/last-visit",
                "{\"lesson_id\":\"" + lesson1Id + "\"}",
                activeStudentToken
        ).statusCode()).isEqualTo(409);
    }

    private void insertUser(UUID userId, String role) {
        db.update(
                "INSERT INTO lms_user_access(user_id, full_name, email, lms_role, access_status) "
                        + "VALUES (?, ?, ?, ?, 'ACTIVE')",
                userId, "User " + userId, userId + "@example.test", role
        );
    }

    private String tokenFor(UUID userId) throws Exception {
        var claims = JWT.claims();
        claims.put("sub", userId.toString());
        return JWT.token(claims);
    }

    private HttpResponse<String> request(String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
