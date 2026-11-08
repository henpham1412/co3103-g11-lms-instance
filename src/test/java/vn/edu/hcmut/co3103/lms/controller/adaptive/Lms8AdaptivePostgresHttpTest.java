package vn.edu.hcmut.co3103.lms.controller.adaptive;

import org.junit.jupiter.api.AfterAll;
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
import org.springframework.dao.DataIntegrityViolationException;
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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Lms8AdaptivePostgresHttpTest.TestClockConfig.class)
class Lms8AdaptivePostgresHttpTest {

    private static final JwtTestSupport JWT = startJwt();

    private static JwtTestSupport startJwt() {
        try {
            return new JwtTestSupport();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
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

    @AfterAll
    static void stopJwks() {
        JWT.close();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate db;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private QuizCreationService quizCreationService;

    @Autowired
    private vn.edu.hcmut.co3103.lms.service.adaptive.SkillTopicAssociationService associationService;

    private UUID ownerId;
    private UUID otherTutorId;
    private UUID studentId;
    private UUID classId;
    private UUID otherClassId;
    private UUID chapterId;
    private UUID lessonId;
    private UUID assignmentActivityId;
    private UUID quizActivityId;
    private UUID quizId;
    private UUID initialQuestionId;

    private String ownerToken;
    private String otherTutorToken;
    private String studentToken;

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @BeforeEach
    void seed() throws Exception {
        ownerId = UUID.randomUUID();
        otherTutorId = UUID.randomUUID();
        studentId = UUID.randomUUID();
        classId = UUID.randomUUID();
        otherClassId = UUID.randomUUID();
        chapterId = UUID.randomUUID();
        lessonId = UUID.randomUUID();

        insertUser(ownerId, "TUTOR");
        insertUser(otherTutorId, "TUTOR");
        insertUser(studentId, "STUDENT");

        db.update("""
                INSERT INTO class(class_id, tutor_id, class_code, title, enrollment_policy, status)
                VALUES (?, ?, ?, 'Adaptive Course', 'OPEN', 'ACTIVE')
                """, classId, ownerId, "AD" + UUID.randomUUID().toString().substring(0, 8));
        db.update("""
                INSERT INTO class(class_id, tutor_id, class_code, title, enrollment_policy, status)
                VALUES (?, ?, ?, 'Other Course', 'OPEN', 'ACTIVE')
                """, otherClassId, otherTutorId, "OT" + UUID.randomUUID().toString().substring(0, 8));
        db.update("""
                INSERT INTO class_member(class_member_id, class_id, student_id, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, UUID.randomUUID(), classId, studentId);
        db.update("""
                INSERT INTO chapter(chapter_id, class_id, title, sequence_order, status)
                VALUES (?, ?, 'Chapter 1', 0, 'PUBLISHED')
                """, chapterId, classId);
        db.update("""
                INSERT INTO lesson(lesson_id, chapter_id, title, sequence_order, status)
                VALUES (?, ?, 'Lesson 1', 0, 'PUBLISHED')
                """, lessonId, chapterId);

        assignmentActivityId = tx().execute(status -> {
            Activity assignment = activityRepository.saveAndFlush(new Activity(
                    lessonId, "Assignment 1", "Desc", Activity.ActivityType.ASSIGNMENT,
                    0, Chapter.VisibilityStatus.PUBLISHED));
            return assignment.getActivityId();
        });

        var createdQuiz = tx().execute(status -> {
            Activity quizAct = activityRepository.saveAndFlush(new Activity(
                    lessonId, "Quiz 1", "Desc", Activity.ActivityType.QUIZ,
                    1, Chapter.VisibilityStatus.HIDDEN));
            return quizCreationService.createForActivity(quizAct.getActivityId(), ownerId, new QuizCreation(
                    30, 2, "HIGHEST", null, OffsetDateTime.now().plusDays(3), null,
                    List.of(new QuestionCreateRequest(
                            "MCQ_SINGLE", "Q1", BigDecimal.TEN, BigDecimal.ZERO, 0,
                            List.of(
                                    new QuestionCreateRequest.Option("Opt A", true, 0),
                                    new QuestionCreateRequest.Option("Opt B", false, 1)
                            ),
                            null
                    ))
            ));
        });
        quizActivityId = createdQuiz.activityId();
        quizId = createdQuiz.quizId();
        initialQuestionId = db.queryForObject(
                "SELECT question_id FROM question WHERE quiz_id = ?", UUID.class, quizId);

        ownerToken = signToken(ownerId);
        otherTutorToken = signToken(otherTutorId);
        studentToken = signToken(studentId);
    }

    @AfterEach
    void cleanup() {
        db.update("DELETE FROM question_skill_topics WHERE question_id IN (SELECT question_id FROM question WHERE quiz_id = ?)", quizId);
        db.update("DELETE FROM activity_skill_topics WHERE activity_id IN (SELECT activity_id FROM activity WHERE lesson_id = ?)", lessonId);
        db.update("DELETE FROM lesson_skill_topics WHERE lesson_id = ?", lessonId);
        db.update("DELETE FROM skill_prerequisites WHERE skill_topic_id IN (SELECT skill_topic_id FROM skill_topics WHERE class_id IN (?, ?))", classId, otherClassId);
        db.update("DELETE FROM skill_topics WHERE class_id IN (?, ?)", classId, otherClassId);
        db.update("DELETE FROM question_option WHERE question_id IN (SELECT question_id FROM question WHERE quiz_id = ?)", quizId);
        db.update("DELETE FROM question WHERE quiz_id = ?", quizId);
        db.update("DELETE FROM quiz WHERE quiz_id = ?", quizId);
        db.update("DELETE FROM activity WHERE lesson_id = ?", lessonId);
        db.update("DELETE FROM lesson WHERE lesson_id = ?", lessonId);
        db.update("DELETE FROM chapter WHERE chapter_id = ?", chapterId);
        db.update("DELETE FROM class_member WHERE class_id IN (?, ?)", classId, otherClassId);
        db.update("DELETE FROM class WHERE class_id IN (?, ?)", classId, otherClassId);
        db.update("DELETE FROM lms_user_access WHERE user_id IN (?, ?, ?)", ownerId, otherTutorId, studentId);
    }

    @Test
    void skillTopicCrudPatchDuplicateAndAuthorizationWorkEndToEndOnPostgres() throws Exception {
        UUID pendingStudentId = UUID.randomUUID();
        UUID inactiveStudentId = UUID.randomUUID();
        UUID nonMemberStudentId = UUID.randomUUID();
        insertUser(pendingStudentId, "STUDENT");
        insertUser(inactiveStudentId, "STUDENT");
        insertUser(nonMemberStudentId, "STUDENT");
        db.update("""
                INSERT INTO class_member(class_member_id, class_id, student_id, status)
                VALUES (?, ?, ?, 'PENDING')
                """, UUID.randomUUID(), classId, pendingStudentId);
        db.update("""
                INSERT INTO class_member(class_member_id, class_id, student_id, status)
                VALUES (?, ?, ?, 'INACTIVE')
                """, UUID.randomUUID(), classId, inactiveStudentId);

        try {
            // ACTIVE student may read, but cannot write
            var activeStudentRead = http("GET", "/classes/" + classId + "/skill-topics", null, studentToken);
            assertThat(activeStudentRead.statusCode()).isEqualTo(200);
            var activeStudentWrite = http("POST", "/classes/" + classId + "/skill-topics",
                    "{\"name\":\"Arrays\",\"difficulty_level\":\"EASY\"}", studentToken);
            assertThat(activeStudentWrite.statusCode()).isEqualTo(403);
            assertThat(readJson(activeStudentWrite).get("code").asString()).isEqualTo("CLASS_ACCESS_DENIED");

            // PENDING, INACTIVE, and non-member students are denied read with 403 CLASS_ACCESS_DENIED
            var pendingResp = http("GET", "/classes/" + classId + "/skill-topics", null, signToken(pendingStudentId));
            assertThat(pendingResp.statusCode()).isEqualTo(403);
            assertThat(readJson(pendingResp).get("code").asString()).isEqualTo("CLASS_ACCESS_DENIED");

            var inactiveResp = http("GET", "/classes/" + classId + "/skill-topics", null, signToken(inactiveStudentId));
            assertThat(inactiveResp.statusCode()).isEqualTo(403);
            assertThat(readJson(inactiveResp).get("code").asString()).isEqualTo("CLASS_ACCESS_DENIED");

            var nonMemberResp = http("GET", "/classes/" + classId + "/skill-topics", null, signToken(nonMemberStudentId));
            assertThat(nonMemberResp.statusCode()).isEqualTo(403);
            assertThat(readJson(nonMemberResp).get("code").asString()).isEqualTo("CLASS_ACCESS_DENIED");

            // Non-owning tutor is denied read and write with 403 CLASS_ACCESS_DENIED
            var otherTutorRead = http("GET", "/classes/" + classId + "/skill-topics", null, otherTutorToken);
            assertThat(otherTutorRead.statusCode()).isEqualTo(403);
            assertThat(readJson(otherTutorRead).get("code").asString()).isEqualTo("CLASS_ACCESS_DENIED");

            var otherTutorResp = http("POST", "/classes/" + classId + "/skill-topics",
                    "{\"name\":\"Arrays\",\"difficulty_level\":\"EASY\"}", otherTutorToken);
            assertThat(otherTutorResp.statusCode()).isEqualTo(403);
            assertThat(readJson(otherTutorResp).get("code").asString()).isEqualTo("CLASS_ACCESS_DENIED");

            // Owning tutor creates topic (stores difficulty 1 in DB, returns EASY)
            var createResp = http("POST", "/classes/" + classId + "/skill-topics",
                    "{\"name\":\"  Arrays  \",\"description\":\"Linear structures\",\"difficulty_level\":\"EASY\"}",
                    ownerToken);
            assertThat(createResp.statusCode()).isEqualTo(201);
            JsonNode created = readJson(createResp);
            UUID topicId = UUID.fromString(created.get("skill_topic_id").asString());
            assertThat(created.get("name").asString()).isEqualTo("Arrays");
            assertThat(created.get("difficulty_level").asString()).isEqualTo("EASY");
            assertThat(db.queryForObject("SELECT difficulty_level FROM skill_topics WHERE skill_topic_id = ?",
                    Integer.class, topicId)).isEqualTo(1);

            // Duplicate name in same class rejected with 409 SKILL_TOPIC_NAME_DUPLICATE
            var dupResp = http("POST", "/classes/" + classId + "/skill-topics",
                    "{\"name\":\"Arrays\",\"difficulty_level\":\"HARD\"}", ownerToken);
            assertThat(dupResp.statusCode()).isEqualTo(409);
            assertThat(readJson(dupResp).get("code").asString()).isEqualTo("SKILL_TOPIC_NAME_DUPLICATE");

            // Partial PATCH updates difficulty and clears description to null while keeping name
            var patchResp = http("PATCH", "/skill-topics/" + topicId,
                    "{\"description\":null,\"difficulty_level\":\"HARD\"}", ownerToken);
            assertThat(patchResp.statusCode()).isEqualTo(200);
            JsonNode patched = readJson(patchResp);
            assertThat(patched.get("name").asString()).isEqualTo("Arrays");
            assertThat(patched.get("description").isNull()).isTrue();
            assertThat(patched.get("difficulty_level").asString()).isEqualTo("HARD");
            assertThat(db.queryForObject("SELECT difficulty_level FROM skill_topics WHERE skill_topic_id = ?",
                    Integer.class, topicId)).isEqualTo(5);

            // Archived class permits GET for owning tutor and ACTIVE student, blocks PENDING/INACTIVE/non-member, and blocks write with 409 RESOURCE_STATE_CONFLICT
            db.update("UPDATE class SET status = 'ARCHIVED' WHERE class_id = ?", classId);
            assertThat(http("GET", "/skill-topics/" + topicId, null, ownerToken).statusCode()).isEqualTo(200);
            assertThat(http("GET", "/skill-topics/" + topicId, null, studentToken).statusCode()).isEqualTo(200);
            assertThat(http("GET", "/skill-topics/" + topicId, null, signToken(pendingStudentId)).statusCode()).isEqualTo(403);
            var archivedPatch = http("PATCH", "/skill-topics/" + topicId,
                    "{\"name\":\"Blocked\"}", ownerToken);
            assertThat(archivedPatch.statusCode()).isEqualTo(409);
            assertThat(readJson(archivedPatch).get("code").asString()).isEqualTo("RESOURCE_STATE_CONFLICT");
            db.update("UPDATE class SET status = 'ACTIVE' WHERE class_id = ?", classId);

            // Unreferenced topic deletes cleanly with 204
            var delResp = http("DELETE", "/skill-topics/" + topicId, null, ownerToken);
            assertThat(delResp.statusCode()).isEqualTo(204);
            assertThat(http("GET", "/skill-topics/" + topicId, null, ownerToken).statusCode()).isEqualTo(404);
        } finally {
            db.update("DELETE FROM class_member WHERE student_id IN (?, ?, ?)",
                    pendingStudentId, inactiveStudentId, nonMemberStudentId);
            db.update("DELETE FROM lms_user_access WHERE user_id IN (?, ?, ?)",
                    pendingStudentId, inactiveStudentId, nonMemberStudentId);
        }
    }

    @Test
    void prerequisiteGraphValidationRejectsSelfReferenceCrossClassAndCyclesAndSupportsReplacement() throws Exception {
        UUID t1 = createTopic(classId, ownerToken, "Variables", "EASY");
        UUID t2 = createTopic(classId, ownerToken, "Loops", "MEDIUM");
        UUID t3 = createTopic(classId, ownerToken, "Recursion", "HARD");
        UUID foreignTopic = createTopic(otherClassId, otherTutorToken, "Foreign", "EASY");

        // Self-reference -> 400 PREREQUISITE_SELF_REFERENCE
        var selfResp = http("PUT", "/skill-topics/" + t1 + "/prerequisites",
                "{\"prerequisite_skill_topic_ids\":[\"" + t1 + "\"]}", ownerToken);
        assertThat(selfResp.statusCode()).isEqualTo(400);
        assertThat(readJson(selfResp).get("code").asString()).isEqualTo("PREREQUISITE_SELF_REFERENCE");

        // Cross-class prerequisite -> 400 PREREQUISITE_CLASS_MISMATCH
        var crossResp = http("PUT", "/skill-topics/" + t2 + "/prerequisites",
                "{\"prerequisite_skill_topic_ids\":[\"" + foreignTopic + "\"]}", ownerToken);
        assertThat(crossResp.statusCode()).isEqualTo(400);
        assertThat(readJson(crossResp).get("code").asString()).isEqualTo("PREREQUISITE_CLASS_MISMATCH");

        // Valid DAG: t2 requires t1, t3 requires t2
        assertThat(http("PUT", "/skill-topics/" + t2 + "/prerequisites",
                "{\"prerequisite_skill_topic_ids\":[\"" + t1 + "\"]}", ownerToken).statusCode()).isEqualTo(204);
        assertThat(http("PUT", "/skill-topics/" + t3 + "/prerequisites",
                "{\"prerequisite_skill_topic_ids\":[\"" + t2 + "\"]}", ownerToken).statusCode()).isEqualTo(204);

        // Direct cycle: t2 requires t3 -> 409 PREREQUISITE_CYCLE
        var directCycle = http("PUT", "/skill-topics/" + t2 + "/prerequisites",
                "{\"prerequisite_skill_topic_ids\":[\"" + t3 + "\"]}", ownerToken);
        assertThat(directCycle.statusCode()).isEqualTo(409);
        assertThat(readJson(directCycle).get("code").asString()).isEqualTo("PREREQUISITE_CYCLE");

        // Indirect cycle: t1 requires t3 -> 409 PREREQUISITE_CYCLE
        var indirectCycle = http("PUT", "/skill-topics/" + t1 + "/prerequisites",
                "{\"prerequisite_skill_topic_ids\":[\"" + t3 + "\"]}", ownerToken);
        assertThat(indirectCycle.statusCode()).isEqualTo(409);
        assertThat(readJson(indirectCycle).get("code").asString()).isEqualTo("PREREQUISITE_CYCLE");

        // Existing valid edge t2 -> t1 remains intact after failed cycle attempt
        var listT2 = readJson(http("GET", "/skill-topics/" + t2 + "/prerequisites", null, ownerToken));
        assertThat(listT2).hasSize(1);
        assertThat(listT2.get(0).get("prerequisite_skill_topic_id").asString()).isEqualTo(t1.toString());

        // Referenced topic cannot be deleted (409 SKILL_TOPIC_REFERENCED)
        var deleteReferenced = http("DELETE", "/skill-topics/" + t1, null, ownerToken);
        assertThat(deleteReferenced.statusCode()).isEqualTo(409);
        assertThat(readJson(deleteReferenced).get("code").asString()).isEqualTo("SKILL_TOPIC_REFERENCED");

        // Clear prerequisites with empty list
        assertThat(http("PUT", "/skill-topics/" + t2 + "/prerequisites",
                "{\"prerequisite_skill_topic_ids\":[]}", ownerToken).statusCode()).isEqualTo(204);
        assertThat(readJson(http("GET", "/skill-topics/" + t2 + "/prerequisites", null, ownerToken))).isEmpty();
    }

    @Test
    void lessonAndActivityAssociationsAndQuestionF7IntegrationMaintainQuizDerivedTopicsAndAtomicity() throws Exception {
        UUID t1 = createTopic(classId, ownerToken, "Topic A", "EASY");
        UUID t2 = createTopic(classId, ownerToken, "Topic B", "MEDIUM");
        UUID t3 = createTopic(classId, ownerToken, "Topic C", "HARD");
        UUID foreignTopic = createTopic(otherClassId, otherTutorToken, "Foreign Topic", "EASY");

        // Lesson topic association replace & read
        var lessonPut = http("PUT", "/lessons/" + lessonId + "/skill-topics",
                "{\"skill_topic_ids\":[\"" + t1 + "\",\"" + t2 + "\"]}", ownerToken);
        assertThat(lessonPut.statusCode()).isEqualTo(200);
        assertThat(readJson(http("GET", "/lessons/" + lessonId + "/skill-topics", null, ownerToken))).hasSize(2);

        // Assignment activity topic association replace & read
        var assignPut = http("PUT", "/activities/" + assignmentActivityId + "/skill-topics",
                "{\"skill_topic_ids\":[\"" + t1 + "\"]}", ownerToken);
        assertThat(assignPut.statusCode()).isEqualTo(200);
        assertThat(readJson(http("GET", "/activities/" + assignmentActivityId + "/skill-topics", null, ownerToken)))
                .hasSize(1);

        // Direct PUT on QUIZ activity topics is rejected with 409 QUIZ_TOPICS_DERIVED
        var quizDirectPut = http("PUT", "/activities/" + quizActivityId + "/skill-topics",
                "{\"skill_topic_ids\":[\"" + t1 + "\"]}", ownerToken);
        assertThat(quizDirectPut.statusCode()).isEqualTo(409);
        assertThat(readJson(quizDirectPut).get("code").asString()).isEqualTo("QUIZ_TOPICS_DERIVED");

        // Update initial Question with skill_topic_ids = [t1, t2] via LMS-5 PATCH /api/v1/questions/{id}
        var patchQ1 = http("PATCH", "/questions/" + initialQuestionId,
                "{\"skill_topic_ids\":[\"" + t1 + "\",\"" + t2 + "\"]}", ownerToken);
        assertThat(patchQ1.statusCode()).isEqualTo(200);

        // Read Question topics via SkillTopicAssociationService.getQuestionTopics
        var q1Topics = vn.edu.hcmut.co3103.lms.service.adaptive.integration.ClassAccessAdapter.withActor(
                ownerId, () -> associationService.getQuestionTopics(initialQuestionId));
        assertThat(q1Topics).hasSize(2);

        // Read Quiz Activity topics -> derived union [t1, t2]
        JsonNode quizTopicsAfterQ1 = readJson(http("GET", "/activities/" + quizActivityId + "/skill-topics", null, ownerToken));
        assertThat(quizTopicsAfterQ1).hasSize(2);
        assertThat(db.queryForObject("SELECT count(*) FROM activity_skill_topics WHERE activity_id = ?",
                Integer.class, quizActivityId)).isEqualTo(2);

        // Create second Question with skill_topic_ids = [t2, t3] via LMS-5 POST /api/v1/activities/{id}/questions
        var createQ2 = http("POST", "/activities/" + quizActivityId + "/questions", """
                {
                  "question_type": "TRUE_FALSE",
                  "content": "Q2 with topics",
                  "points_awarded": 5,
                  "points_deducted": 0,
                  "order_index": 1,
                  "options": [
                    {"content": "True", "is_correct": true, "order_index": 0},
                    {"content": "False", "is_correct": false, "order_index": 1}
                  ],
                  "skill_topic_ids": ["%s", "%s"]
                }
                """.formatted(t2, t3), ownerToken);
        assertThat(createQ2.statusCode()).isEqualTo(201);
        UUID q2Id = UUID.fromString(readJson(createQ2).get("question_id").asString());

        // Quiz Activity derived union is now [t1, t2, t3]
        assertThat(readJson(http("GET", "/activities/" + quizActivityId + "/skill-topics", null, ownerToken))).hasSize(3);

        // Atomicity & rollback check: creating a question with a cross-class topic rolls back the Question insert
        int questionCountBefore = db.queryForObject(
                "SELECT count(*) FROM question WHERE quiz_id = ?", Integer.class, quizId);
        var badCreateQ3 = http("POST", "/activities/" + quizActivityId + "/questions", """
                {
                  "question_type": "TRUE_FALSE",
                  "content": "Q3 atomic rollback",
                  "points_awarded": 5,
                  "points_deducted": 0,
                  "order_index": 2,
                  "options": [
                    {"content": "True", "is_correct": true, "order_index": 0},
                    {"content": "False", "is_correct": false, "order_index": 1}
                  ],
                  "skill_topic_ids": ["%s"]
                }
                """.formatted(foreignTopic), ownerToken);
        assertThat(badCreateQ3.statusCode()).isEqualTo(400);
        assertThat(readJson(badCreateQ3).get("code").asString()).isEqualTo("RESOURCE_CLASS_MISMATCH");
        int questionCountAfter = db.queryForObject(
                "SELECT count(*) FROM question WHERE quiz_id = ?", Integer.class, quizId);
        assertThat(questionCountAfter).isEqualTo(questionCountBefore);

        // Delete Q2 -> its question_skill_topics rows are removed and Quiz Activity union shrinks back to [t1, t2]
        var deleteQ2 = http("DELETE", "/questions/" + q2Id, null, ownerToken);
        assertThat(deleteQ2.statusCode()).isEqualTo(204);
        assertThat(readJson(http("GET", "/activities/" + quizActivityId + "/skill-topics", null, ownerToken))).hasSize(2);
        assertThat(db.queryForObject("SELECT count(*) FROM activity_skill_topics WHERE activity_id = ? AND skill_topic_id = ?",
                Integer.class, quizActivityId, t3)).isZero();

        // Restrictive FK check at PostgreSQL level: cannot delete lesson while lesson_skill_topics references it
        assertThatThrownBy(() -> db.update("DELETE FROM lesson WHERE lesson_id = ?", lessonId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Check difficulty constraint at PostgreSQL level: difficulty 2 is rejected
        assertThatThrownBy(() -> db.update("""
                INSERT INTO skill_topics(skill_topic_id, class_id, name, difficulty_level)
                VALUES (?, ?, 'Invalid Diff', 2)
                """, UUID.randomUUID(), classId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID createTopic(UUID targetClassId, String token, String name, String difficulty) throws Exception {
        var response = http("POST", "/classes/" + targetClassId + "/skill-topics",
                "{\"name\":\"" + name + "\",\"difficulty_level\":\"" + difficulty + "\"}", token);
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(readJson(response).get("skill_topic_id").asString());
    }

    private void insertUser(UUID id, String role) {
        db.update("""
                INSERT INTO lms_user_access(user_id, full_name, email, lms_role, access_status)
                VALUES (?, 'Test User', ?, ?, 'ACTIVE')
                """, id, id + "@example.test", role);
    }

    private String signToken(UUID userId) throws Exception {
        var claims = JWT.claims();
        claims.put("sub", userId.toString());
        return JWT.token(claims);
    }

    private HttpResponse<String> http(String method, String path, String body, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode readJson(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }
}
