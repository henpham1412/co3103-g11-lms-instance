package vn.edu.hcmut.co3103.lms.controller.assessment;

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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end HTTP + PostgreSQL regression tests for DEFECT-01 (atomic Quiz creation & publication
 * gating) and DEFECT-02 (Tutor Quiz details contract with real questions and Student secrecy).
 * Every Quiz, Question, Option, and Attempt row is created exclusively through HTTP APIs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(QuizIntegrationPostgresHttpTest.Clocks.class)
class QuizIntegrationPostgresHttpTest {
    private static final JwtTestSupport JWT = initJwt();

    private static JwtTestSupport initJwt() {
        try {
            return new JwtTestSupport();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
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
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> JwtTestSupport.ISSUER);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwksUri);
        r.add("lms.quiz.expiry-scan-ms", () -> "3600000");
    }

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate db;
    @Autowired
    ObjectMapper mapper;

    private UUID tutorId;
    private UUID otherTutorId;
    private UUID studentId;
    private UUID classId;
    private UUID chapterId;
    private UUID lessonId;
    private UUID topicId;
    private String tutorToken;
    private String otherTutorToken;
    private String studentToken;

    @BeforeEach
    void seedCourseShell() throws Exception {
        tutorId = UUID.randomUUID();
        otherTutorId = UUID.randomUUID();
        studentId = UUID.randomUUID();
        classId = UUID.randomUUID();
        chapterId = UUID.randomUUID();
        lessonId = UUID.randomUUID();
        topicId = UUID.randomUUID();

        db.update("INSERT INTO lms_user_access(user_id, full_name, email, lms_role, access_status) VALUES (?,?,?,'TUTOR','ACTIVE')",
                tutorId, "Owner Tutor", tutorId + "@example.invalid");
        db.update("INSERT INTO lms_user_access(user_id, full_name, email, lms_role, access_status) VALUES (?,?,?,'TUTOR','ACTIVE')",
                otherTutorId, "Other Tutor", otherTutorId + "@example.invalid");
        db.update("INSERT INTO lms_user_access(user_id, full_name, email, lms_role, access_status) VALUES (?,?,?,'STUDENT','ACTIVE')",
                studentId, "Active Student", studentId + "@example.invalid");

        db.update("INSERT INTO class(class_id, tutor_id, class_code, title, enrollment_policy, status) VALUES (?,?,?,'Quiz Integration','OPEN','ACTIVE')",
                classId, tutorId, "QI" + UUID.randomUUID().toString().substring(0, 7));
        db.update("INSERT INTO class_member(class_member_id, class_id, student_id, status) VALUES (?,?,?,'ACTIVE')",
                UUID.randomUUID(), classId, studentId);
        db.update("INSERT INTO chapter(chapter_id, class_id, title, sequence_order, status) VALUES (?,?,'Chapter 1',0,'PUBLISHED')",
                chapterId, classId);
        db.update("INSERT INTO lesson(lesson_id, chapter_id, title, sequence_order, status) VALUES (?,?,'Lesson 1',0,'PUBLISHED')",
                lessonId, chapterId);
        db.update("INSERT INTO skill_topics(skill_topic_id, class_id, name, difficulty_level) VALUES (?,?,'Algorithms',3)",
                topicId, classId);

        tutorToken = token(tutorId);
        otherTutorToken = token(otherTutorId);
        studentToken = token(studentId);
    }

    @AfterEach
    void cleanup() {
        db.update("DELETE FROM learning_signal WHERE class_member_id IN (SELECT class_member_id FROM class_member WHERE class_id = ?)", classId);
        db.update("DELETE FROM learner_mastery WHERE class_member_id IN (SELECT class_member_id FROM class_member WHERE class_id = ?)", classId);
        db.update("DELETE FROM attempt_answer_option WHERE answer_id IN (SELECT answer_id FROM attempt_answer WHERE attempt_id IN (SELECT attempt_id FROM quiz_attempt WHERE quiz_id IN (SELECT quiz_id FROM quiz WHERE activity_id IN (SELECT activity_id FROM activity WHERE lesson_id = ?))))", lessonId);
        db.update("DELETE FROM attempt_answer WHERE attempt_id IN (SELECT attempt_id FROM quiz_attempt WHERE quiz_id IN (SELECT quiz_id FROM quiz WHERE activity_id IN (SELECT activity_id FROM activity WHERE lesson_id = ?)))", lessonId);
        db.update("DELETE FROM quiz_attempt WHERE quiz_id IN (SELECT quiz_id FROM quiz WHERE activity_id IN (SELECT activity_id FROM activity WHERE lesson_id = ?))", lessonId);
        db.update("DELETE FROM question_skill_topics WHERE question_id IN (SELECT question_id FROM question WHERE quiz_id IN (SELECT quiz_id FROM quiz WHERE activity_id IN (SELECT activity_id FROM activity WHERE lesson_id = ?)))", lessonId);
        db.update("DELETE FROM question_option WHERE question_id IN (SELECT question_id FROM question WHERE quiz_id IN (SELECT quiz_id FROM quiz WHERE activity_id IN (SELECT activity_id FROM activity WHERE lesson_id = ?)))", lessonId);
        db.update("DELETE FROM question WHERE quiz_id IN (SELECT quiz_id FROM quiz WHERE activity_id IN (SELECT activity_id FROM activity WHERE lesson_id = ?))", lessonId);
        db.update("DELETE FROM quiz WHERE activity_id IN (SELECT activity_id FROM activity WHERE lesson_id = ?)", lessonId);
        db.update("DELETE FROM activity_skill_topics WHERE activity_id IN (SELECT activity_id FROM activity WHERE lesson_id = ?)", lessonId);
        db.update("DELETE FROM activity WHERE lesson_id = ?", lessonId);
        db.update("DELETE FROM skill_topics WHERE class_id = ?", classId);
        db.update("DELETE FROM lesson WHERE lesson_id = ?", lessonId);
        db.update("DELETE FROM chapter WHERE chapter_id = ?", chapterId);
        db.update("DELETE FROM class_member WHERE class_id = ?", classId);
        db.update("DELETE FROM class WHERE class_id = ?", classId);
        db.update("DELETE FROM notification WHERE recipient_id IN (?,?,?)", tutorId, otherTutorId, studentId);
        db.update("DELETE FROM lms_user_access WHERE user_id IN (?,?,?)", tutorId, otherTutorId, studentId);
    }

    private String token(UUID userId) throws Exception {
        var claims = JWT.claims();
        claims.put("sub", userId.toString());
        return JWT.token(claims);
    }

    private HttpResponse<String> http(String method, String path, String body, String bearer) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "application/json")
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }

    @Test
    void completeTutorAndStudentQuizWorkflowEnforcesContractsAndPostAttemptLocks() throws Exception {
        String deadline = OffsetDateTime.now().plusDays(3).toString();

        // 1. Tutor creates a HIDDEN Quiz with embedded quiz settings and no initial questions.
        HttpResponse<String> created = http("POST", "/lessons/" + lessonId + "/activities", """
                {
                  "title": "Week 1 Diagnostic Quiz",
                  "description": "Check fundamentals",
                  "activity_type": "QUIZ",
                  "sequence_order": 0,
                  "status": "HIDDEN",
                  "quiz": {
                    "time_limit_minutes": 15,
                    "attempts_allowed": 2,
                    "grading_method": "HIGHEST",
                    "opening_time": null,
                    "deadline": "%s",
                    "required_score": 60
                  }
                }
                """.formatted(deadline), tutorToken);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode activityJson = json(created);
        UUID activityId = UUID.fromString(activityJson.get("activity_id").asString());
        assertThat(activityJson.get("status").asString()).isEqualTo("HIDDEN");
        assertThat(db.queryForObject("SELECT count(*) FROM notification WHERE recipient_id = ?", Long.class, studentId))
                .isZero();

        // 2. Open and reload Quiz details: Tutor gets activity metadata + quiz config + real empty questions [].
        HttpResponse<String> activityRead = http("GET", "/activities/" + activityId, null, tutorToken);
        assertThat(activityRead.statusCode()).isEqualTo(200);
        assertThat(json(activityRead).get("title").asString()).isEqualTo("Week 1 Diagnostic Quiz");

        HttpResponse<String> tutorQuizEmpty = http("GET", "/activities/" + activityId + "/quiz", null, tutorToken);
        assertThat(tutorQuizEmpty.statusCode()).isEqualTo(200);
        JsonNode tutorQuizEmptyJson = json(tutorQuizEmpty);
        UUID quizId = UUID.fromString(tutorQuizEmptyJson.get("quiz_id").asString());
        assertThat(tutorQuizEmptyJson.get("activity_id").asString()).isEqualTo(activityId.toString());
        assertThat(tutorQuizEmptyJson.get("time_limit_minutes").asInt()).isEqualTo(15);
        assertThat(tutorQuizEmptyJson.has("questions")).isTrue();
        assertThat(tutorQuizEmptyJson.get("questions").isArray()).isTrue();
        assertThat(tutorQuizEmptyJson.get("questions")).isEmpty();

        // Authorization checks on hidden quiz
        assertThat(http("GET", "/activities/" + activityId + "/quiz", null, otherTutorToken).statusCode()).isEqualTo(403);
        assertThat(http("GET", "/activities/" + activityId + "/quiz", null, studentToken).statusCode()).isEqualTo(404);

        // 3. Cannot publish a Quiz with zero questions.
        HttpResponse<String> publishEmpty = http("PATCH", "/activities/" + activityId, "{\"status\":\"PUBLISHED\"}", tutorToken);
        assertThat(publishEmpty.statusCode()).isEqualTo(409);
        assertThat(json(publishEmpty).get("code").asString()).isEqualTo("ASSESSMENT_CONFIGURATION_MISMATCH");
        assertThat(db.queryForObject("SELECT status FROM activity WHERE activity_id = ?", String.class, activityId))
                .isEqualTo("HIDDEN");
        assertThat(db.queryForObject("SELECT count(*) FROM notification WHERE recipient_id = ?", Long.class, studentId))
                .isZero();

        // 4. Add Q1 (with Adaptive Learning skill_topic_ids) and Q2.
        HttpResponse<String> addQ1 = http("POST", "/activities/" + activityId + "/questions", """
                {
                  "question_type": "MCQ_SINGLE",
                  "content": "What is O(1)?",
                  "points_awarded": 10,
                  "points_deducted": 0,
                  "order_index": 0,
                  "options": [
                    {"content": "Constant time", "is_correct": true, "order_index": 0},
                    {"content": "Linear time", "is_correct": false, "order_index": 1}
                  ],
                  "skill_topic_ids": ["%s"]
                }
                """.formatted(topicId), tutorToken);
        assertThat(addQ1.statusCode()).isEqualTo(201);
        JsonNode q1Json = json(addQ1);
        UUID q1Id = UUID.fromString(q1Json.get("question_id").asString());
        UUID q1OptA = UUID.fromString(q1Json.get("options").get(0).get("option_id").asString());
        UUID q1OptB = UUID.fromString(q1Json.get("options").get(1).get("option_id").asString());

        HttpResponse<String> addQ2 = http("POST", "/activities/" + activityId + "/questions", """
                {
                  "question_type": "TRUE_FALSE",
                  "content": "Arrays have O(1) index access.",
                  "points_awarded": 5,
                  "points_deducted": 0,
                  "order_index": 1,
                  "options": [
                    {"content": "True", "is_correct": true, "order_index": 0},
                    {"content": "False", "is_correct": false, "order_index": 1}
                  ]
                }
                """, tutorToken);
        assertThat(addQ2.statusCode()).isEqualTo(201);
        UUID q2Id = UUID.fromString(json(addQ2).get("question_id").asString());

        // Verify Adaptive Learning topic synchronization persisted from Q1
        HttpResponse<String> activityTopics = http("GET", "/activities/" + activityId + "/skill-topics", null, tutorToken);
        assertThat(activityTopics.statusCode()).isEqualTo(200);
        assertThat(json(activityTopics)).hasSize(1);

        // 5. Reload Quiz details as Tutor: both persisted questions and option answer keys appear in order.
        JsonNode tutorReloaded = json(http("GET", "/activities/" + activityId + "/quiz", null, tutorToken));
        assertThat(tutorReloaded.get("questions")).hasSize(2);
        assertThat(tutorReloaded.get("questions").get(0).get("question_id").asString()).isEqualTo(q1Id.toString());
        assertThat(tutorReloaded.get("questions").get(0).get("options").get(0).get("is_correct").asBoolean()).isTrue();
        assertThat(tutorReloaded.get("questions").get(1).get("question_id").asString()).isEqualTo(q2Id.toString());

        // 6. Edit Q1 (including adding a new option without option_id) and delete Q2.
        HttpResponse<String> editQ1 = http("PATCH", "/questions/" + q1Id, """
                {
                  "content": "Which complexity is constant time?",
                  "points_awarded": 20,
                  "options": [
                    {"option_id": "%s", "content": "O(1)", "is_correct": true, "order_index": 0},
                    {"option_id": "%s", "content": "O(n)", "is_correct": false, "order_index": 1},
                    {"content": "O(log n)", "is_correct": false, "order_index": 2}
                  ]
                }
                """.formatted(q1OptA, q1OptB), tutorToken);
        assertThat(editQ1.statusCode()).isEqualTo(200);
        UUID q1OptC = UUID.fromString(json(editQ1).get("options").get(2).get("option_id").asString());

        assertThat(http("DELETE", "/questions/" + q2Id, null, tutorToken).statusCode()).isEqualTo(204);

        JsonNode afterEditAndDelete = json(http("GET", "/activities/" + activityId + "/quiz", null, tutorToken));
        assertThat(afterEditAndDelete.get("questions")).hasSize(1);
        assertThat(afterEditAndDelete.get("questions").get(0).get("content").asString())
                .isEqualTo("Which complexity is constant time?");
        assertThat(afterEditAndDelete.get("questions").get(0).get("options")).hasSize(3);

        // 7. Publish valid Quiz and verify single CONTENT_RELEASED notification to Student.
        HttpResponse<String> published = http("PATCH", "/activities/" + activityId, "{\"status\":\"PUBLISHED\"}", tutorToken);
        assertThat(published.statusCode()).isEqualTo(200);
        assertThat(json(published).get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(db.queryForList(
                "SELECT target_url FROM notification WHERE recipient_id = ? AND notification_type = 'CONTENT_RELEASED'",
                String.class, studentId))
                .containsExactly("/classes/" + classId + "/lessons/" + lessonId + "/activities/" + activityId);

        // 8. Student Quiz workflow: Student gets settings WITHOUT questions or is_correct keys.
        HttpResponse<String> studentQuizRead = http("GET", "/activities/" + activityId + "/quiz", null, studentToken);
        assertThat(studentQuizRead.statusCode()).isEqualTo(200);
        JsonNode studentQuizJson = json(studentQuizRead);
        assertThat(studentQuizJson.get("quiz_id").asString()).isEqualTo(quizId.toString());
        assertThat(studentQuizJson.get("activity_id").asString()).isEqualTo(activityId.toString());
        assertThat(studentQuizJson.has("questions")).isFalse();
        assertThat(studentQuizRead.body()).doesNotContain("is_correct");

        // Student starts attempt, answers correctly, and submits.
        HttpResponse<String> started = http("POST", "/quizzes/" + quizId + "/attempts", null, studentToken);
        assertThat(started.statusCode()).isEqualTo(201);
        UUID attemptId = UUID.fromString(json(started).get("attempt_id").asString());
        assertThat(started.body()).doesNotContain("is_correct");

        assertThat(http("PUT", "/quiz-attempts/" + attemptId + "/answers/" + q1Id,
                "{\"selected_option_ids\":[\"" + q1OptA + "\"]}", studentToken).statusCode()).isEqualTo(204);
        HttpResponse<String> submitted = http("POST", "/quiz-attempts/" + attemptId + "/submit", null, studentToken);
        assertThat(submitted.statusCode()).isEqualTo(200);
        assertThat(new BigDecimal(json(submitted).get("score").asString())).isEqualByComparingTo("100.00");

        // 9. Tutor views attempts.
        HttpResponse<String> attemptsList = http("GET", "/quizzes/" + quizId + "/attempts", null, tutorToken);
        assertThat(attemptsList.statusCode()).isEqualTo(200);
        JsonNode attemptsJson = json(attemptsList);
        assertThat(attemptsJson.get("items")).hasSize(1);
        assertThat(attemptsJson.get("items").get(0).get("attempt_id").asString()).isEqualTo(attemptId.toString());

        // 10. Post-attempt restrictions enforced.
        HttpResponse<String> lockedAdd = http("POST", "/activities/" + activityId + "/questions", """
                {
                  "question_type": "TRUE_FALSE",
                  "content": "Late question",
                  "points_awarded": 5,
                  "order_index": 5,
                  "options": [
                    {"content": "True", "is_correct": true, "order_index": 0},
                    {"content": "False", "is_correct": false, "order_index": 1}
                  ]
                }
                """, tutorToken);
        assertThat(lockedAdd.statusCode()).isEqualTo(409);
        assertThat(json(lockedAdd).get("code").asString()).isEqualTo("QUESTION_EDIT_LOCKED");

        HttpResponse<String> lockedDelete = http("DELETE", "/questions/" + q1Id, null, tutorToken);
        assertThat(lockedDelete.statusCode()).isEqualTo(409);
        assertThat(json(lockedDelete).get("code").asString()).isEqualTo("QUESTION_EDIT_LOCKED");

        HttpResponse<String> lockedTypeChange = http("PATCH", "/questions/" + q1Id,
                "{\"question_type\":\"MCQ_MULTIPLE\"}", tutorToken);
        assertThat(lockedTypeChange.statusCode()).isEqualTo(409);
        assertThat(json(lockedTypeChange).get("code").asString()).isEqualTo("QUESTION_EDIT_LOCKED");

        HttpResponse<String> lockedDropOption = http("PATCH", "/questions/" + q1Id, """
                {
                  "options": [
                    {"option_id": "%s", "content": "O(1)", "is_correct": true, "order_index": 0},
                    {"option_id": "%s", "content": "O(n)", "is_correct": false, "order_index": 1}
                  ]
                }
                """.formatted(q1OptA, q1OptB), tutorToken);
        assertThat(lockedDropOption.statusCode()).isEqualTo(409);
        assertThat(json(lockedDropOption).get("code").asString()).isEqualTo("QUESTION_EDIT_LOCKED");

        // Permitted grading-key edit keeping all existing options triggers automatic regrading.
        HttpResponse<String> permittedRegrade = http("PATCH", "/questions/" + q1Id, """
                {
                  "options": [
                    {"option_id": "%s", "content": "O(1)", "is_correct": false, "order_index": 0},
                    {"option_id": "%s", "content": "O(n)", "is_correct": true, "order_index": 1},
                    {"option_id": "%s", "content": "O(log n)", "is_correct": false, "order_index": 2}
                  ]
                }
                """.formatted(q1OptA, q1OptB, q1OptC), tutorToken);
        assertThat(permittedRegrade.statusCode()).isEqualTo(200);
        assertThat(db.queryForObject("SELECT score FROM quiz_attempt WHERE attempt_id = ?", BigDecimal.class, attemptId))
                .isEqualByComparingTo("0.00");

        // Hiding the quiz hides it from students while preserving Tutor access.
        assertThat(http("PATCH", "/activities/" + activityId, "{\"status\":\"HIDDEN\"}", tutorToken).statusCode()).isEqualTo(200);
        assertThat(http("GET", "/activities/" + activityId + "/quiz", null, studentToken).statusCode()).isEqualTo(404);
        assertThat(http("GET", "/activities/" + activityId + "/quiz", null, tutorToken).statusCode()).isEqualTo(200);
    }

    @Test
    void atomicQuizCreationRejectsInvalidPayloadsAndSupportsPublishedQuizWithInitialQuestions() throws Exception {
        String deadline = OffsetDateTime.now().plusDays(2).toString();

        // Published Quiz without questions -> 409 and full rollback (no Activity or Quiz persisted).
        HttpResponse<String> publishedEmpty = http("POST", "/lessons/" + lessonId + "/activities", """
                {
                  "title": "Published Without Questions",
                  "activity_type": "QUIZ",
                  "status": "PUBLISHED",
                  "quiz": {
                    "time_limit_minutes": 15,
                    "deadline": "%s"
                  }
                }
                """.formatted(deadline), tutorToken);
        assertThat(publishedEmpty.statusCode()).isEqualTo(409);
        assertThat(json(publishedEmpty).get("code").asString()).isEqualTo("ASSESSMENT_CONFIGURATION_MISMATCH");

        // Fractional time_limit_minutes -> 400 VALIDATION_ERROR and full rollback.
        HttpResponse<String> fractionalTime = http("POST", "/lessons/" + lessonId + "/activities", """
                {
                  "title": "Bad Time",
                  "activity_type": "QUIZ",
                  "status": "HIDDEN",
                  "quiz": {
                    "time_limit_minutes": 15.5,
                    "deadline": "%s"
                  }
                }
                """.formatted(deadline), tutorToken);
        assertThat(fractionalTime.statusCode()).isEqualTo(400);
        assertThat(json(fractionalTime).get("code").asString()).isEqualTo("VALIDATION_ERROR");

        // Invalid nested initial question -> 400 INVALID_QUESTION and full rollback.
        HttpResponse<String> badQuestion = http("POST", "/lessons/" + lessonId + "/activities", """
                {
                  "title": "Bad Question",
                  "activity_type": "QUIZ",
                  "status": "HIDDEN",
                  "quiz": {
                    "time_limit_minutes": 15,
                    "deadline": "%s",
                    "questions": [
                      {
                        "question_type": "MCQ_SINGLE",
                        "content": "No correct option",
                        "points_awarded": 10,
                        "order_index": 0,
                        "options": [
                          {"content": "A", "is_correct": false, "order_index": 0},
                          {"content": "B", "is_correct": false, "order_index": 1}
                        ]
                      }
                    ]
                  }
                }
                """.formatted(deadline), tutorToken);
        assertThat(badQuestion.statusCode()).isEqualTo(400);
        assertThat(json(badQuestion).get("code").asString()).isEqualTo("INVALID_QUESTION");

        // Unauthorized callers rejected without writing.
        assertThat(http("POST", "/lessons/" + lessonId + "/activities", """
                {"title":"Other","activity_type":"QUIZ","status":"HIDDEN","quiz":{"time_limit_minutes":10,"deadline":"%s"}}
                """.formatted(deadline), otherTutorToken).statusCode()).isEqualTo(403);
        assertThat(http("POST", "/lessons/" + lessonId + "/activities", """
                {"title":"Student","activity_type":"QUIZ","status":"HIDDEN","quiz":{"time_limit_minutes":10,"deadline":"%s"}}
                """.formatted(deadline), studentToken).statusCode()).isEqualTo(403);

        assertThat(db.queryForObject("SELECT count(*) FROM activity WHERE lesson_id = ?", Integer.class, lessonId))
                .isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM quiz", Integer.class)).isZero();

        // Published Quiz WITH valid initial question and skill_topic_ids succeeds atomically and notifies Student once.
        HttpResponse<String> publishedWithQuestion = http("POST", "/lessons/" + lessonId + "/activities", """
                {
                  "title": "Published Atomic Quiz",
                  "activity_type": "QUIZ",
                  "status": "PUBLISHED",
                  "quiz": {
                    "time_limit_minutes": 20,
                    "deadline": "%s",
                    "questions": [
                      {
                        "question_type": "TRUE_FALSE",
                        "content": "Java is statically typed.",
                        "points_awarded": 5,
                        "order_index": 0,
                        "options": [
                          {"content": "True", "is_correct": true, "order_index": 0},
                          {"content": "False", "is_correct": false, "order_index": 1}
                        ],
                        "skill_topic_ids": ["%s"]
                      }
                    ]
                  }
                }
                """.formatted(deadline, topicId), tutorToken);
        assertThat(publishedWithQuestion.statusCode()).isEqualTo(201);
        UUID activityId = UUID.fromString(json(publishedWithQuestion).get("activity_id").asString());
        assertThat(db.queryForList(
                "SELECT target_url FROM notification WHERE recipient_id = ? AND notification_type = 'CONTENT_RELEASED'",
                String.class, studentId))
                .containsExactly("/classes/" + classId + "/lessons/" + lessonId + "/activities/" + activityId);
        JsonNode topics = json(http("GET", "/activities/" + activityId + "/skill-topics", null, tutorToken));
        assertThat(topics).hasSize(1);
    }
}
