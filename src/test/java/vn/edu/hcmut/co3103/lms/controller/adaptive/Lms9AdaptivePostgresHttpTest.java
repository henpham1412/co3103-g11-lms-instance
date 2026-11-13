package vn.edu.hcmut.co3103.lms.controller.adaptive;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.net.http.*;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Real HTTP, signed JWT/JWKS, local authorization and PostgreSQL; no mocked security. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(Lms9AdaptivePostgresHttpTest.Clocks.class)
class Lms9AdaptivePostgresHttpTest {
    static final JwtTestSupport JWT = jwt();
    static JwtTestSupport jwt() { try { return new JwtTestSupport(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @TestConfiguration(proxyBeanMethods = false) static class Clocks {
        @Bean @Primary Clock clock() { return JwtTestSupport.CLOCK; }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> JwtTestSupport.ISSUER);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwksUri);
        r.add("lms.quiz.expiry-scan-ms", () -> "3600000");
    }
    @AfterAll static void stopJwks() { JWT.close(); }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    final HttpClient http = HttpClient.newHttpClient();
    final List<UUID> users = new ArrayList<>(), courses = new ArrayList<>();
    UUID owner, otherTutor, student, otherStudent, admin, course, foreign, member, foreignMember, chapter, lesson, a, b, c;
    Quiz first, second;

    @BeforeEach void seed() {
        assertThat(db.queryForObject("SELECT version()", String.class)).contains("PostgreSQL 18");
        owner = user("TUTOR"); otherTutor = user("TUTOR"); student = user("STUDENT"); otherStudent = user("STUDENT"); admin = user("ADMINISTRATOR");
        course = course(owner); foreign = course(otherTutor);
        member = member(course, student); foreignMember = member(foreign, otherStudent);
        chapter = UUID.randomUUID(); lesson = UUID.randomUUID();
        db.update("INSERT INTO chapter(chapter_id,class_id,title,sequence_order) VALUES (?,?,'Chapter',0)", chapter, course);
        db.update("INSERT INTO lesson(lesson_id,chapter_id,title,sequence_order) VALUES (?,?,'Lesson',0)", lesson, chapter);
        a = topic("Topic A"); b = topic("Topic B"); c = topic("Topic C");
        first = quiz(0, a); second = quiz(1, b);
    }

    @AfterEach void cleanupOwnFixtures() {
        for (UUID courseId : courses) {
            var params = new Object[]{courseId};
            String quizzes = "SELECT q.quiz_id FROM quiz q JOIN activity act ON act.activity_id=q.activity_id JOIN lesson l ON l.lesson_id=act.lesson_id JOIN chapter ch ON ch.chapter_id=l.chapter_id WHERE ch.class_id=?";
            db.update("DELETE FROM attempt_answer_option WHERE answer_id IN (SELECT answer_id FROM attempt_answer WHERE attempt_id IN (SELECT attempt_id FROM quiz_attempt WHERE quiz_id IN (" + quizzes + ")))", params);
            db.update("DELETE FROM attempt_answer WHERE attempt_id IN (SELECT attempt_id FROM quiz_attempt WHERE quiz_id IN (" + quizzes + "))", params);
            db.update("DELETE FROM quiz_attempt WHERE quiz_id IN (" + quizzes + ")", params);
            db.update("DELETE FROM question_skill_topics WHERE question_id IN (SELECT question_id FROM question WHERE quiz_id IN (" + quizzes + "))", params);
            db.update("DELETE FROM question_option WHERE question_id IN (SELECT question_id FROM question WHERE quiz_id IN (" + quizzes + "))", params);
            db.update("DELETE FROM question WHERE quiz_id IN (" + quizzes + ")", params);
            db.update("DELETE FROM activity_skill_topics WHERE skill_topic_id IN (SELECT skill_topic_id FROM skill_topics WHERE class_id=?)", params);
            db.update("DELETE FROM lesson_skill_topics WHERE skill_topic_id IN (SELECT skill_topic_id FROM skill_topics WHERE class_id=?)", params);
            db.update("DELETE FROM skill_prerequisites WHERE skill_topic_id IN (SELECT skill_topic_id FROM skill_topics WHERE class_id=?)", params);
            db.update("DELETE FROM skill_topics WHERE class_id=?", params);
            db.update("DELETE FROM quiz WHERE quiz_id IN (" + quizzes + ")", params);
            db.update("DELETE FROM activity WHERE lesson_id IN (SELECT l.lesson_id FROM lesson l JOIN chapter ch ON ch.chapter_id=l.chapter_id WHERE ch.class_id=?)", params);
            db.update("DELETE FROM completion_mark WHERE class_member_id IN (SELECT class_member_id FROM class_member WHERE class_id=?)", params);
            db.update("DELETE FROM last_visit WHERE class_member_id IN (SELECT class_member_id FROM class_member WHERE class_id=?)", params);
            db.update("DELETE FROM lesson WHERE chapter_id IN (SELECT chapter_id FROM chapter WHERE class_id=?)", params);
            db.update("DELETE FROM chapter WHERE class_id=?", params);
            db.update("DELETE FROM class_member WHERE class_id=?", params); db.update("DELETE FROM class WHERE class_id=?", params);
        }
        for (UUID user : users) db.update("DELETE FROM lms_user_access WHERE user_id=?", user);
    }

    @Test void studentReceivesDoc05ShapeWithOnlyGenuineCandidateTopics() throws Exception {
        mastery(b, "10");
        var body = get(studentPath(), student);
        assertThat(body.get("class_id").asText()).isEqualTo(course.toString());
        assertThat(body.has("reason")).isFalse();
        var rec = body.get("recommendation");
        assertThat(rec.size()).isEqualTo(5); assertThat(rec.get("activity_id").asText()).isEqualTo(second.activity().toString());
        assertThat(rec.get("activity_type").asText()).isEqualTo("QUIZ");
        assertThat(rec.get("title").asText()).isEqualTo("Quiz 1");
        assertThat(rec.get("reason").asText()).isEqualTo("ADDITIONAL_PRACTICE");
        var topic = rec.get("skill_topics").get(0);
        assertThat(topic.size()).isEqualTo(3); assertThat(topic.get("skill_topic_id").asText()).isEqualTo(b.toString());
        assertThat(topic.get("name").asText()).isEqualTo("Topic B"); assertThat(topic.get("mastery_level").asText()).isEqualTo("WEAK");
        assertThat(body.toString()).doesNotContain("source_id", "recorded_at", "learning_signal", "reinforcedTopicId", "quiz_id");
    }

    @ParameterizedTest @ValueSource(strings = {"0", "60", "80"})
    void studentWeakDevelopingAndSufficientMapPersistedMasteryWithoutChangingRanking(String score) throws Exception {
        mastery(a, score);
        var rec = get(studentPath(), student).get("recommendation");
        boolean sufficient = score.equals("80");
        assertThat(rec.get("activity_id").asText()).isEqualTo((sufficient ? second : first).activity().toString());
        assertThat(rec.get("reason").asText()).isEqualTo(sufficient ? "NORMAL_SEQUENCE" : "ADDITIONAL_PRACTICE");
        if (!sufficient) assertThat(rec.get("skill_topics").get(0).get("mastery_level").asText()).isEqualTo(score.equals("0") ? "WEAK" : "DEVELOPING");
    }

    @Test void studentNoEvidenceHasNullLevelAndDoesNotFabricateWeakOrScore() throws Exception {
        var rec = get(studentPath(), student).get("recommendation");
        assertThat(rec.get("reason").asText()).isEqualTo("NORMAL_SEQUENCE");
        assertThat(rec.get("skill_topics").get(0).get("mastery_level").isNull()).isTrue();
        assertThat(rec.get("skill_topics").get(0).has("mastery_score")).isFalse();
    }

    @Test void studentNoEligibleActivityReturns200NullRecommendationAndReason() throws Exception {
        db.update("UPDATE activity SET status='HIDDEN' WHERE lesson_id=?", lesson);
        var body = get(studentPath(), student);
        assertThat(body.get("recommendation").isNull()).isTrue(); assertThat(body.get("reason").asText()).isEqualTo("NO_ELIGIBLE_ACTIVITY");
    }

    @ParameterizedTest @ValueSource(strings = {"activity", "lesson", "chapter"})
    void studentCannotSeeHiddenResourcesOrDescendants(String table) throws Exception {
        UUID id = table.equals("activity") ? first.activity() : table.equals("lesson") ? lesson : chapter;
        db.update("UPDATE " + table + " SET status='HIDDEN' WHERE " + table + "_id=?", id);
        mastery(a, "0"); var body = get(studentPath(), student);
        if (table.equals("activity")) assertThat(body.get("recommendation").get("activity_id").asText()).isEqualTo(second.activity().toString());
        else assertThat(body.get("recommendation").isNull()).isTrue();
        assertThat(body.toString()).doesNotContain(first.activity().toString(), a.toString());
    }

    @Test void studentArchivedClassHasNoNewRecommendationWhileResumeRemainsReadable() throws Exception {
        db.update("UPDATE class SET status='ARCHIVED' WHERE class_id=?", course);
        var body = get(studentPath(), student);
        assertThat(body.get("recommendation").isNull()).isTrue(); assertThat(body.get("reason").asText()).isEqualTo("CLASS_ARCHIVED");
        assertThat(get(resumePath(), student).get("target_type").asText()).isEqualTo("LESSON");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void studentUnavailableQuizIsNotReturned(boolean expired) throws Exception {
        if (expired) db.update("UPDATE quiz SET deadline=? WHERE quiz_id=?", OffsetDateTime.now().minusHours(1), first.id());
        else db.update("UPDATE quiz SET opening_time=? WHERE quiz_id=?", OffsetDateTime.now().plusHours(1), first.id());
        mastery(a, "0"); assertThat(get(studentPath(), student).get("recommendation").get("activity_id").asText()).isEqualTo(second.activity().toString());
    }

    @Test void studentExhaustedQuizIsNotReturned() throws Exception {
        db.update("UPDATE quiz SET attempts_allowed=1 WHERE quiz_id=?", first.id()); gradedAttempt(false);
        assertThat(get(studentPath(), student).get("recommendation").get("activity_id").asText()).isEqualTo(second.activity().toString());
    }

    @Test void studentRecommendationNeverOverwritesAvailableQuizResumeOrMutatesAttempt() throws Exception {
        UUID id = start(first); var before = get(resumePath(), student);
        var state = db.queryForMap("SELECT * FROM quiz_attempt WHERE attempt_id=?", id);
        mastery(a, "0");
        assertThat(get(studentPath(), student).get("recommendation").get("activity_id").asText()).isEqualTo(second.activity().toString());
        assertThat(get(resumePath(), student)).isEqualTo(before);
        assertThat(before.get("target_type").asText()).isEqualTo("QUIZ_ATTEMPT"); assertThat(before.get("target_id").asText()).isEqualTo(id.toString());
        assertThat(db.queryForMap("SELECT * FROM quiz_attempt WHERE attempt_id=?", id)).isEqualTo(state);
    }

    @Test void studentLastVisitFirstIncompleteAndOutlineResumePrioritiesRemainUnchanged() throws Exception {
        assertThat(get(resumePath(), student).get("reason").asText()).isEqualTo("FIRST_INCOMPLETE_LESSON");
        checked("PUT", "/classes/" + course + "/last-visit", "{\"lesson_id\":\"" + lesson + "\"}", student, 200);
        var lastVisit = get(resumePath(), student); get(studentPath(), student);
        assertThat(get(resumePath(), student)).isEqualTo(lastVisit);
        assertThat(lastVisit.get("reason").asText()).isEqualTo("MOST_RECENTLY_VISITED_LESSON");
        checked("PUT", completionPath(), null, student, 200);
        var outline = get(resumePath(), student); get(studentPath(), student);
        assertThat(outline.get("target_type").asText()).isEqualTo("CLASS_OUTLINE");
        assertThat(outline.get("reason").asText()).isEqualTo("NO_UNFINISHED_TARGET");
        assertThat(get(resumePath(), student)).isEqualTo(outline);
    }

    @Test void studentIdentityComesFromJwtIgnoringClientSuppliedLearnerSelectors() throws Exception {
        UUID otherMember = member(course, otherStudent);
        db.update("INSERT INTO learner_mastery(class_member_id,skill_topic_id,mastery_level,mastery_score) VALUES (?,?,'WEAK',0)", otherMember, b);
        var body = get(studentPath() + "?student_id=" + otherStudent + "&class_member_id=" + otherMember, student);
        assertThat(body.get("recommendation").get("activity_id").asText()).isEqualTo(first.activity().toString());
    }

    @Test void studentNonMemberAndCrossClassRequestsAreDenied() throws Exception {
        error(studentPath(), otherStudent, 403, "MEMBERSHIP_REQUIRED");
        error("/classes/" + foreign + "/recommended-activities", student, 403, "MEMBERSHIP_REQUIRED");
    }

    @ParameterizedTest @ValueSource(strings = {"INACTIVE", "PENDING"})
    void studentNonActiveMembershipIsDenied(String status) throws Exception {
        db.update("UPDATE class_member SET status=? WHERE class_member_id=?", status, member);
        error(studentPath(), student, 403, "MEMBERSHIP_REQUIRED");
    }

    @Test void studentSuspendedAndUnknownLocalAccessAreDenied() throws Exception {
        db.update("UPDATE lms_user_access SET access_status='SUSPENDED' WHERE user_id=?", student);
        error(studentPath(), student, 403, "FORBIDDEN"); error(studentPath(), UUID.randomUUID(), 403, "FORBIDDEN");
    }

    @ParameterizedTest @ValueSource(strings = {"TUTOR", "ADMINISTRATOR"})
    void studentEndpointHasNoRoleImpersonationOverride(String role) throws Exception {
        error(studentPath() + "?student_id=" + student, role.equals("TUTOR") ? owner : admin, 403, "FORBIDDEN");
    }

    @Test void studentJwtIsValidatedBeforeAccessAndMalformedPathUsesCommonErrorEnvelope() throws Exception {
        var noToken = request("GET", studentPath(), null, null); assertError(noToken, 401, "UNAUTHORIZED");
        assertError(request("GET", studentPath(), null, "not-a-jwt"), 401, "UNAUTHORIZED");
        var claims = JWT.claims(); claims.put("sub", student.toString()); claims.put("exp", JwtTestSupport.NOW.minusSeconds(120).getEpochSecond());
        assertError(request("GET", studentPath(), null, JWT.token(claims)), 401, "UNAUTHORIZED");
        error("/classes/not-a-uuid/recommended-activities", student, 400, "INVALID_QUERY_PARAMETER");
    }

    @Test void tutorOwningSummaryIncludesAllClassTopicsWithExplicitNoEvidenceAndActualZero() throws Exception {
        mastery(a, "0"); mastery(b, "85.5");
        var body = get(masteryPath(), owner); assertThat(body.size()).isEqualTo(3);
        assertThat(body.get("class_id").asText()).isEqualTo(course.toString()); assertThat(body.get("class_member_id").asText()).isEqualTo(member.toString());
        assertThat(body.get("items").size()).isEqualTo(3);
        assertMastery(item(body, a), "0", "WEAK", true); assertMastery(item(body, b), "85.5", "SUFFICIENT", true);
        assertMastery(item(body, c), null, null, false);
        assertThat(body.toString()).doesNotContain("source_id", "learning_signal", "recorded_at", "student_id", "updated_at");
    }

    @Test void tutorSummaryPreservesPersistedPrecisionWithoutRecalculationOrDisplayRounding() throws Exception {
        String score = "79.99999999999999999999999999999999"; mastery(a, score);
        var response = checked("GET", masteryPath(), null, owner, 200);
        assertThat(response.body()).contains(score, "DEVELOPING");
        assertThat(db.queryForObject("SELECT mastery_score FROM learner_mastery WHERE class_member_id=? AND skill_topic_id=?", BigDecimal.class, member, a)).isEqualByComparingTo(score);
    }

    @Test void tutorSummaryReflectsCanonicalRegradingAndOriginalSubmissionTime() throws Exception {
        UUID attempt = gradedAttempt(false);
        var original = db.queryForObject("SELECT submitted_at FROM quiz_attempt WHERE attempt_id=?", OffsetDateTime.class, attempt);
        assertMastery(item(get(masteryPath(), owner), a), "0", "WEAK", true);
        String options = "{\"options\":[{\"option_id\":\"" + first.right() + "\",\"content\":\"Right\",\"is_correct\":false,\"order_index\":0},{\"option_id\":\"" + first.wrong() + "\",\"content\":\"Wrong\",\"is_correct\":true,\"order_index\":1}]}";
        checked("PATCH", "/questions/" + first.question(), options, owner, 200);
        assertMastery(item(get(masteryPath(), owner), a), "100", "SUFFICIENT", true);
        assertThat(db.queryForObject("SELECT submitted_at FROM quiz_attempt WHERE attempt_id=?", OffsetDateTime.class, attempt)).isEqualTo(original);
    }

    @Test void tutorSummaryReflectsCanonicalTopicRemappingAndRemovesStaleMastery() throws Exception {
        gradedAttempt(true);
        assertMastery(item(get(masteryPath(), owner), a), "100", "SUFFICIENT", true);
        checked("PATCH", "/questions/" + first.question(), "{\"skill_topic_ids\":[\"" + b + "\",\"" + c + "\"]}", owner, 200);
        var body = get(masteryPath(), owner);
        assertMastery(item(body, a), null, null, false); assertMastery(item(body, b), "100", "SUFFICIENT", true); assertMastery(item(body, c), "100", "SUFFICIENT", true);
        assertThat(body.get("items").size()).isEqualTo(3);
    }

    @Test void tutorNonOwnerCannotReadSummaryOrMemberProgress() throws Exception {
        error(masteryPath(), otherTutor, 403, "FORBIDDEN"); error(progressPath(), otherTutor, 403, "FORBIDDEN");
    }

    @Test void tutorRouteHasNoAdministratorOrStudentOverride() throws Exception {
        error(masteryPath(), admin, 403, "FORBIDDEN"); error(masteryPath(), student, 403, "FORBIDDEN");
    }

    @Test void tutorWrongClassMemberAndMissingResourcesUse404WithoutLeakingMastery() throws Exception {
        error("/tutor/classes/" + course + "/members/" + foreignMember + "/mastery", owner, 404, "CLASS_OR_MEMBER_NOT_FOUND");
        error("/tutor/classes/" + course + "/members/" + UUID.randomUUID() + "/mastery", owner, 404, "CLASS_OR_MEMBER_NOT_FOUND");
        error("/tutor/classes/" + UUID.randomUUID() + "/members/" + member + "/mastery", owner, 404, "CLASS_OR_MEMBER_NOT_FOUND");
    }

    @ParameterizedTest @ValueSource(strings = {"INACTIVE", "PENDING"})
    void tutorCannotReadFormerOrPendingMembers(String status) throws Exception {
        mastery(a, "80"); db.update("UPDATE class_member SET status=? WHERE class_member_id=?", status, member);
        error(masteryPath(), owner, 404, "CLASS_OR_MEMBER_NOT_FOUND"); error(progressPath(), owner, 404, "CLASS_OR_MEMBER_NOT_FOUND");
    }

    @Test void tutorSuspendedAccessIsDeniedWithOtherwiseValidJwt() throws Exception {
        db.update("UPDATE lms_user_access SET access_status='SUSPENDED' WHERE user_id=?", owner);
        error(masteryPath(), owner, 403, "FORBIDDEN"); error(progressPath(), owner, 403, "FORBIDDEN");
    }

    @Test void tutorArchivedClassRemainsReadOnlyHistoryForOwnerAndActiveMember() throws Exception {
        mastery(a, "80"); db.update("UPDATE class SET status='ARCHIVED' WHERE class_id=?", course);
        assertMastery(item(get(masteryPath(), owner), a), "80", "SUFFICIENT", true);
        assertThat(get(progressPath(), owner).get("skill_mastery").size()).isEqualTo(3);
    }

    @Test void tutorHiddenTopicsRemainInOwnerSummaryButForeignClassTopicsDoNotLeak() throws Exception {
        db.update("UPDATE chapter SET status='HIDDEN' WHERE chapter_id=?", chapter);
        UUID otherTopic = UUID.randomUUID();
        db.update("INSERT INTO skill_topics(skill_topic_id,class_id,name,difficulty_level) VALUES (?,?,'Foreign topic',1)", otherTopic, foreign);
        // Malformed derived row must not bypass the class equality enforced by the shared query.
        db.update("INSERT INTO learner_mastery(class_member_id,skill_topic_id,mastery_level,mastery_score) VALUES (?,?,'WEAK',0)", member, otherTopic);
        var body = get(masteryPath(), owner); assertThat(body.get("items").size()).isEqualTo(3);
        assertThat(body.toString()).doesNotContain(otherTopic.toString(), "Foreign topic");
    }

    @Test void progressUsesSameMasteryItemsWithoutChangingCompletionOrAssessmentHistory() throws Exception {
        UUID attempt = gradedAttempt(false); checked("PUT", completionPath(), null, student, 200);
        var before = get(progressPath(), owner);
        mastery(b, "66.66666666666666666666666666666667");
        var after = get(progressPath(), owner); var summary = get(masteryPath(), owner);
        assertThat(after.get("skill_mastery").size()).isEqualTo(3);
        for (JsonNode m : summary.get("items")) {
            JsonNode match = null;
            for (JsonNode p : after.get("skill_mastery")) if (p.get("skill_topic_id").equals(m.get("skill_topic_id"))) match = p;
            assertThat(match).isNotNull(); assertThat(match.size()).isEqualTo(3);
            assertThat(match.get("name")).isEqualTo(m.get("name")); assertThat(match.get("level")).isEqualTo(m.get("mastery_level"));
        }
        for (String field : List.of("class_id", "class_member_id", "student_id", "membership_status", "completed_lessons", "eligible_lessons", "progress_percentage", "status", "chapters", "assessment_outcomes"))
            assertThat(after.get(field)).as(field).isEqualTo(before.get(field));
        assertThat(after.get("completed_lessons").asInt()).isEqualTo(1); assertThat(after.get("progress_percentage").asInt()).isEqualTo(100);
        assertThat(after.get("assessment_outcomes").size()).isEqualTo(1);
        assertThat(after.get("assessment_outcomes").get(0).get("attempt_id").asText()).isEqualTo(attempt.toString());
        assertThat(after.get("assessment_outcomes").get(0).get("score").asDouble()).isZero();
    }

    @Test void progressIncludesNoEvidenceTopicsAndIsEmptyOnlyWhenClassHasNoTopics() throws Exception {
        var items = get(progressPath(), owner).get("skill_mastery"); assertThat(items.size()).isEqualTo(3);
        for (JsonNode item : items) assertThat(item.get("level").isNull()).isTrue();
        db.update("DELETE FROM question_skill_topics WHERE question_id IN (?,?)", first.question(), second.question());
        db.update("DELETE FROM skill_topics WHERE class_id=?", course);
        assertThat(get(progressPath(), owner).get("skill_mastery").size()).isZero(); assertThat(get(masteryPath(), owner).get("items").size()).isZero();
    }

    private JsonNode get(String path, UUID user) throws Exception { return json.readTree(checked("GET", path, null, user, 200).body()); }
    private String studentPath() { return "/classes/" + course + "/recommended-activities"; }
    private String masteryPath() { return "/tutor/classes/" + course + "/members/" + member + "/mastery"; }
    private String progressPath() { return "/tutor/classes/" + course + "/members/" + member + "/progress"; }
    private String resumePath() { return "/classes/" + course + "/resume-target"; }
    private String completionPath() { return "/classes/" + course + "/lessons/" + lesson + "/completion"; }
    private JsonNode item(JsonNode response, UUID topic) { for (JsonNode item : response.get("items")) if (item.get("skill_topic_id").asText().equals(topic.toString())) return item; throw new AssertionError("Topic absent: " + topic); }
    private void assertMastery(JsonNode item, String score, String level, boolean available) {
        assertThat(item.size()).isEqualTo(5); assertThat(item.get("evidence_available").asBoolean()).isEqualTo(available);
        if (score == null) { assertThat(item.get("mastery_score").isNull()).isTrue(); assertThat(item.get("mastery_level").isNull()).isTrue(); }
        else { assertThat(new BigDecimal(item.get("mastery_score").asText())).isEqualByComparingTo(score); assertThat(item.get("mastery_level").asText()).isEqualTo(level); }
    }
    private void error(String path, UUID user, int status, String code) throws Exception { assertError(request("GET", path, null, token(user)), status, code); }
    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var body = json.readTree(response.body()); assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.has("message")).isTrue(); assertThat(body.get("details").isArray()).isTrue();
        assertThat(body.toString()).doesNotContain("stackTrace", "learning_signal", "source_id");
    }
    private HttpResponse<String> checked(String method, String path, String body, UUID user, int status) throws Exception {
        var response = request(method, path, body, token(user)); assertThat(response.statusCode()).as(response.body()).isEqualTo(status); return response;
    }
    private HttpResponse<String> request(String method, String path, String body, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).header("Content-Type", "application/json");
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private String token(UUID id) throws Exception { var claims = JWT.claims(); claims.put("sub", id.toString()); return JWT.token(claims); }
    private UUID start(Quiz quiz) throws Exception { return UUID.fromString(json.readTree(checked("POST", "/quizzes/" + quiz.id() + "/attempts", null, student, 201).body()).get("attempt_id").asText()); }
    private UUID gradedAttempt(boolean correct) throws Exception {
        UUID attempt = start(first);
        checked("PUT", "/quiz-attempts/" + attempt + "/answers/" + first.question(), "{\"selected_option_ids\":[\"" + (correct ? first.right() : first.wrong()) + "\"]}", student, 204);
        checked("POST", "/quiz-attempts/" + attempt + "/submit", null, student, 200); return attempt;
    }
    private void mastery(UUID topic, String value) { BigDecimal score = new BigDecimal(value); String level = score.compareTo(new BigDecimal("50")) < 0 ? "WEAK" : score.compareTo(new BigDecimal("80")) < 0 ? "DEVELOPING" : "SUFFICIENT"; db.update("INSERT INTO learner_mastery(class_member_id,skill_topic_id,mastery_level,mastery_score) VALUES (?,?,?,?)", member, topic, level, score); }
    private UUID user(String role) { UUID id = UUID.randomUUID(); users.add(id); db.update("INSERT INTO lms_user_access(user_id,full_name,email,lms_role) VALUES (?,'LMS9 HTTP',?,?)", id, id + "@example.test", role); return id; }
    private UUID course(UUID tutor) { UUID id = UUID.randomUUID(); courses.add(id); db.update("INSERT INTO class(class_id,tutor_id,class_code,title,enrollment_policy) VALUES (?,?,?,'LMS9 API','OPEN')", id, tutor, id.toString().substring(0, 30)); return id; }
    private UUID member(UUID course, UUID student) { UUID id = UUID.randomUUID(); db.update("INSERT INTO class_member(class_member_id,class_id,student_id,status) VALUES (?,?,?,'ACTIVE')", id, course, student); return id; }
    private UUID topic(String name) { UUID id = UUID.randomUUID(); db.update("INSERT INTO skill_topics(skill_topic_id,class_id,name,difficulty_level) VALUES (?,?,?,1)", id, course, name); return id; }
    private Quiz quiz(int order, UUID topic) {
        UUID act = UUID.randomUUID(), id = UUID.randomUUID(), q = UUID.randomUUID(), right = UUID.randomUUID(), wrong = UUID.randomUUID();
        db.update("INSERT INTO activity(activity_id,lesson_id,title,activity_type,sequence_order) VALUES (?,?,?,'QUIZ',?)", act, lesson, "Quiz " + order, order);
        db.update("INSERT INTO quiz(quiz_id,activity_id,time_limit_minutes,attempts_allowed,deadline) VALUES (?,?,30,3,?)", id, act, OffsetDateTime.now().plusDays(1));
        db.update("INSERT INTO question(question_id,quiz_id,question_type,content,points_awarded,order_index) VALUES (?,?,'MCQ_SINGLE','Question',1,0)", q, id);
        db.update("INSERT INTO question_option(option_id,question_id,content,is_correct,order_index) VALUES (?,?,'Right',true,0),(?,?,'Wrong',false,1)", right, q, wrong, q);
        db.update("INSERT INTO question_skill_topics(question_id,skill_topic_id) VALUES (?,?)", q, topic); return new Quiz(id, act, q, right, wrong);
    }
    private record Quiz(UUID id, UUID activity, UUID question, UUID right, UUID wrong) { }
}
