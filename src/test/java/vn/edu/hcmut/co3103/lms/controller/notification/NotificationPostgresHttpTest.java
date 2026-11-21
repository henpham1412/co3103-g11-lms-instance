package vn.edu.hcmut.co3103.lms.controller.notification;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.edu.hcmut.co3103.lms.dto.request.MaterialRequest;
import vn.edu.hcmut.co3103.lms.entity.Chapter.VisibilityStatus;
import vn.edu.hcmut.co3103.lms.entity.Material.MaterialType;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;
import vn.edu.hcmut.co3103.lms.service.learningcontent.ChapterService;
import vn.edu.hcmut.co3103.lms.service.notification.NotificationEvents;
import vn.edu.hcmut.co3103.lms.service.notification.NotificationMaintenance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "lms.notifications.reminder-cron=-", "lms.notifications.cleanup-cron=-", "lms.quiz.expiry-scan-ms=3600000"
})
@Import(NotificationPostgresHttpTest.FixedClock.class)
class NotificationPostgresHttpTest {
    private static final JwtTestSupport JWT = startJwt();
    private static final OffsetDateTime NOW = JwtTestSupport.NOW.atOffset(ZoneOffset.UTC).plusDays(5);
    private static final String INBOX = "/notifications";
    private static final String MARK_READ = "{\"is_read\":true}";
    private static final String PUBLISH = "{\"status\":\"PUBLISHED\"}";

    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired NotificationMaintenance maintenance;
    @Autowired NotificationEvents events;
    @Autowired ChapterService content;
    @Autowired PlatformTransactionManager transactions;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<UUID> users = new ArrayList<>();
    private UUID tutor;
    private UUID student;
    private UUID other;
    private UUID suspended;
    private UUID course;
    private UUID chapter;
    private UUID lesson;
    private UUID studentMembership;
    private UUID otherMembership;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean @Primary
        Clock clock() { return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC); }
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> JwtTestSupport.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwksUri);
    }

    @AfterAll static void closeJwt() { JWT.close(); }

    @BeforeEach
    void seedClass() {
        tutor = user("TUTOR", "ACTIVE");
        student = user("STUDENT", "ACTIVE");
        other = user("STUDENT", "ACTIVE");
        suspended = user("STUDENT", "SUSPENDED");
        course = UUID.randomUUID();
        chapter = UUID.randomUUID();
        lesson = UUID.randomUUID();
        db.update("""
                INSERT INTO class(class_id, tutor_id, class_code, title, enrollment_policy)
                VALUES (?, ?, ?, 'Notifications class', 'APPROVAL_REQUIRED')
                """, course, tutor, "N-" + course.toString().substring(0, 12));
        studentMembership = membership(student);
        otherMembership = membership(other);
        membership(suspended);
        db.update("INSERT INTO chapter(chapter_id,class_id,title,sequence_order) VALUES (?,?,'Chapter',0)", chapter, course);
        db.update("INSERT INTO lesson(lesson_id,chapter_id,title,sequence_order) VALUES (?,?,'Lesson',0)", lesson, chapter);
    }

    @AfterEach
    void deleteFixtures() {
        db.update("DELETE FROM discussion WHERE class_id=?", course);
        String activities = "SELECT activity_id FROM activity WHERE lesson_id=?";
        String quizzes = "SELECT quiz_id FROM quiz WHERE activity_id IN (" + activities + ")";
        db.update("DELETE FROM quiz_attempt WHERE quiz_id IN (" + quizzes + ")", lesson);
        db.update("DELETE FROM question_option WHERE question_id IN (SELECT question_id FROM question WHERE quiz_id IN ("
                + quizzes + "))", lesson);
        db.update("DELETE FROM question WHERE quiz_id IN (" + quizzes + ")", lesson);
        db.update("DELETE FROM quiz WHERE activity_id IN (" + activities + ")", lesson);
        db.update("DELETE FROM assignment WHERE activity_id IN (" + activities + ")", lesson);
        db.update("DELETE FROM activity WHERE lesson_id=?", lesson);
        db.update("DELETE FROM material WHERE lesson_id=?", lesson);
        db.update("DELETE FROM lesson WHERE lesson_id=?", lesson);
        db.update("DELETE FROM chapter WHERE chapter_id=?", chapter);
        db.update("DELETE FROM class_member WHERE class_id=?", course);
        db.update("DELETE FROM class WHERE class_id=?", course);
        for (UUID id : users) {
            db.update("DELETE FROM notification WHERE recipient_id=?", id);
            db.update("DELETE FROM lms_user_access WHERE user_id=?", id);
        }
    }

    @Test
    void listsOnlyCurrentRecipientsInboxAndMarksReadIdempotently() throws Exception {
        UUID oldest = notice(student, NOW.minusDays(2));
        UUID newest = notice(student, NOW.minusDays(1));
        UUID foreign = notice(other, NOW);
        var previousReadAt = NOW.minusHours(1);
        db.update("UPDATE notification SET is_read=TRUE, read_at=? WHERE notification_id=?", previousReadAt, oldest);

        var first = request("GET", INBOX + "?size=1", student, null, 200);
        assertThat(first.get("page").asInt()).isEqualTo(1);
        assertThat(first.get("size").asInt()).isEqualTo(1);
        assertThat(first.get("total_items").asLong()).isEqualTo(2);
        assertThat(first.get("total_pages").asInt()).isEqualTo(2);
        var item = first.get("items").get(0);
        assertThat(item.get("notification_id").asString()).isEqualTo(newest.toString());
        assertThat(item.get("notification_type").asString()).isEqualTo("CONTENT_RELEASED");
        assertThat(item.get("is_read").asBoolean()).isFalse();
        assertThat(item.get("read_at").isNull()).isTrue();
        assertThat(item.has("source_event_key")).isFalse();
        assertThat(item.has("recipient_id")).isFalse();
        assertThat(request("GET", INBOX + "?size=1&page=2", student, null, 200)
                .get("items").get(0).get("notification_id").asString()).isEqualTo(oldest.toString());
        assertThat(request("GET", INBOX + "?is_read=true", student, null, 200).get("total_items").asLong()).isEqualTo(1);
        assertThat(request("GET", INBOX + "?is_read=false", student, null, 200).get("total_items").asLong()).isEqualTo(1);
        assertThat(request("PATCH", INBOX + "/" + foreign, student, MARK_READ, 403).get("code").asString())
                .isEqualTo("FORBIDDEN");
        assertThat(request("PATCH", INBOX + "/" + UUID.randomUUID(), student, MARK_READ, 404).get("code").asString())
                .isEqualTo("NOTIFICATION_NOT_FOUND");
        assertThat(request("GET", INBOX, null, null, 401).get("code").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(request("GET", INBOX, suspended, null, 403).get("code").asString()).isEqualTo("FORBIDDEN");
        var alreadyRead = request("PATCH", INBOX + "/" + oldest, student, MARK_READ, 200);
        assertThat(OffsetDateTime.parse(alreadyRead.get("read_at").asString())).isEqualTo(previousReadAt);

        var patch = httpRequest("PATCH", INBOX + "/" + newest, student, MARK_READ);
        var left = http.sendAsync(patch, HttpResponse.BodyHandlers.ofString());
        var right = http.sendAsync(patch, HttpResponse.BodyHandlers.ofString());
        var firstRead = response(left.get(10, TimeUnit.SECONDS), 200);
        var secondRead = response(right.get(10, TimeUnit.SECONDS), 200);
        assertThat(firstRead.get("is_read").asBoolean()).isTrue();
        assertThat(OffsetDateTime.parse(firstRead.get("read_at").asString())).isEqualTo(NOW);
        assertThat(secondRead.get("read_at")).isEqualTo(firstRead.get("read_at"));
        assertThat(request("GET", INBOX + "?is_read=false", student, null, 200).get("total_items").asLong()).isZero();
        assertThat(request("GET", INBOX + "?is_read=true", student, null, 200).get("total_items").asLong()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT is_read FROM notification WHERE notification_id=?", Boolean.class, foreign))
                .isFalse();
    }

    @Test
    void rejectsInvalidFilterPaginationAndReadTransition() throws Exception {
        UUID id = notice(student, NOW);
        for (String query : List.of("?is_read=no", "?is_read=", "?page=0", "?size=101", "?page=invalid")) {
            request("GET", INBOX + query, student, null, 400);
        }
        for (String body : List.of("{}", "{\"is_read\":false}", "{\"is_read\":\"true\"}",
                "{\"is_read\":null}", "{\"is_read\":true,\"read_at\":null}")) {
            request("PATCH", INBOX + "/" + id, student, body, 400);
        }
        request("PATCH", INBOX + "/invalid", student, MARK_READ, 400);
        assertThat(db.queryForMap("SELECT is_read, read_at FROM notification WHERE notification_id=?", id))
                .containsEntry("is_read", false).containsEntry("read_at", null);
    }

    @Test
    void contentReleaseUsesEffectiveVisibilityAndKeepsSourceAndNotificationAtomic() throws Exception {
        db.update("UPDATE class_member SET status='PENDING' WHERE class_member_id=?", otherMembership);
        request("PATCH", "/chapters/" + chapter, tutor, "{\"status\":\"HIDDEN\"}", 200);
        var material = request("POST", "/lessons/" + lesson + "/materials", tutor,
                """
                {"title":"Visible notes","content":"notes","material_type":"TEXT","status":"PUBLISHED"}
                """, 201);
        String materialPath = "/materials/" + material.get("material_id").asString();
        assertThat(titles(student, "CONTENT_RELEASED")).isEmpty();
        request("PATCH", "/chapters/" + chapter, tutor, PUBLISH, 200);
        request("PATCH", materialPath, tutor, PUBLISH, 200);
        request("PATCH", materialPath, tutor, "{\"title\":\"Renamed notes\"}", 200);
        assertThat(messages(student, "CONTENT_RELEASED")).containsExactly("Now available: Visible notes");

        request("PATCH", materialPath, tutor, "{\"status\":\"HIDDEN\"}", 200);
        request("PATCH", materialPath, tutor, PUBLISH, 200);
        assertThat(messages(student, "CONTENT_RELEASED"))
                .containsExactlyInAnyOrder("Now available: Visible notes", "Now available: Renamed notes");
        request("PATCH", "/lessons/" + lesson, tutor, "{\"status\":\"HIDDEN\"}", 200);
        request("PATCH", "/lessons/" + lesson, tutor, PUBLISH, 200);
        assertThat(messages(student, "CONTENT_RELEASED")).containsExactlyInAnyOrder(
                "Now available: Visible notes", "Now available: Renamed notes", "Now available: Renamed notes");
        for (UUID ineligible : List.of(tutor, other, suspended)) {
            assertThat(titles(ineligible, "CONTENT_RELEASED")).isEmpty();
        }

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            content.createMaterial(lesson, tutor,
                    new MaterialRequest("Rolled-back material", MaterialType.TEXT, null, "body", null, VisibilityStatus.PUBLISHED));
            assertThat(messages(student, "CONTENT_RELEASED")).contains("Now available: Rolled-back material");
            status.setRollbackOnly();
        });
        assertThat(messages(student, "CONTENT_RELEASED")).hasSize(3);
        assertThat(db.queryForObject("SELECT count(*) FROM material WHERE lesson_id=?", Long.class, lesson)).isEqualTo(1);
    }

    @Test
    void membershipDecisionsAndRepliesReachEligibleRecipientsWithoutDuplicates() throws Exception {
        db.update("UPDATE class_member SET status='PENDING' WHERE class_id=? AND student_id IN (?,?)", course, student, other);
        String memberPath = "/classes/" + course + "/members/";
        request("POST", memberPath + studentMembership + "/approve", tutor, null, 200);
        request("POST", memberPath + otherMembership + "/reject", tutor, null, 200);
        request("DELETE", memberPath + studentMembership, tutor, null, 204);
        assertThat(titles(student, "ENROLLMENT_UPDATE"))
                .containsExactlyInAnyOrder("Your class request was approved", "You were removed from a class");
        assertThat(titles(other, "ENROLLMENT_UPDATE")).containsExactly("Your class request was declined");
        assertThat(titles(tutor, "ENROLLMENT_UPDATE")).isEmpty();

        db.update("UPDATE class_member SET status='ACTIVE' WHERE class_id=? AND student_id IN (?,?)", course, student, other);
        UUID root = discussionId(request("POST", "/classes/" + course + "/discussions", tutor,
                "{\"title\":\"Thread\",\"content\":\"Question\"}", 201));
        UUID firstReply = discussionId(request("POST", "/discussions/" + root + "/replies", student,
                "{\"content\":\"First answer\"}", 201));
        UUID secondReply = discussionId(request("POST", "/discussions/" + firstReply + "/replies", other,
                "{\"content\":\"Follow-up answer\"}", 201));
        new TransactionTemplate(transactions).executeWithoutResult(status -> events.discussionReplied(secondReply));
        assertThat(titles(tutor, "DISCUSSION_REPLY")).hasSize(2);
        assertThat(titles(student, "DISCUSSION_REPLY")).hasSize(1);
        assertThat(titles(other, "DISCUSSION_REPLY")).isEmpty();
        assertThat(titles(suspended, "DISCUSSION_REPLY")).isEmpty();
        db.update("UPDATE class_member SET status='INACTIVE' WHERE class_member_id=?", studentMembership);
        request("POST", "/discussions/" + secondReply + "/replies", tutor, "{\"content\":\"Closing reply\"}", 201);
        assertThat(titles(tutor, "DISCUSSION_REPLY")).hasSize(2);
        assertThat(titles(student, "DISCUSSION_REPLY")).hasSize(1);
        assertThat(titles(other, "DISCUSSION_REPLY")).hasSize(1);
    }

    @Test
    void scansOpenVisibleUnfinishedAssessmentsOncePerDeadline() {
        UUID due = assignment("At the boundary", "PUBLISHED", null, NOW.plusHours(24));
        UUID submitted = assignment("Submitted", "PUBLISHED", null, NOW.plusHours(2));
        assignment("Later", "PUBLISHED", null, NOW.plusHours(24).plusSeconds(1));
        assignment("Expired", "PUBLISHED", null, NOW);
        assignment("Hidden", "HIDDEN", null, NOW.plusHours(2));
        assignment("Not open", "PUBLISHED", NOW.plusHours(1), NOW.plusHours(2));
        db.update("""
                INSERT INTO submission(assignment_id,class_member_id,file_name,storage_path,mime_type,size_bytes)
                VALUES (?,?,'work.txt','fixture-work','text/plain',1)
                """, submitted, studentMembership);
        UUID quiz = quiz("Unfinished quiz", NOW.plusHours(2));
        UUID expiredAttemptQuiz = quiz("Expired attempt quiz", NOW.plusHours(2));
        db.update("INSERT INTO quiz_attempt(quiz_id,class_member_id,attempt_number,status,started_at,submitted_at)"
                + " VALUES (?,?,1,'SUBMITTED',?,?)", quiz, otherMembership, NOW.minusMinutes(10), NOW.minusMinutes(5));
        db.update("INSERT INTO quiz_attempt(quiz_id,class_member_id,attempt_number,started_at) VALUES (?,?,1,?)",
                quiz, studentMembership, NOW.minusMinutes(5));
        db.update("INSERT INTO quiz_attempt(quiz_id,class_member_id,attempt_number,started_at) VALUES (?,?,1,?)",
                expiredAttemptQuiz, studentMembership, NOW.minusMinutes(20));

        // The maintenance job scans all classes. Roll back its effects outside these fixtures.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            db.update("UPDATE lesson SET status='HIDDEN' WHERE lesson_id=?", lesson);
            maintenance.sendDeadlineReminders();
            assertThat(messages(student, "DEADLINE_REMINDER")).isEmpty();
            db.update("UPDATE lesson SET status='PUBLISHED' WHERE lesson_id=?", lesson);
            maintenance.sendDeadlineReminders();
            maintenance.sendDeadlineReminders();
            assertThat(messages(student, "DEADLINE_REMINDER")).containsExactlyInAnyOrder(
                    "Due within 24 hours: At the boundary", "Due within 24 hours: Unfinished quiz");
            assertThat(messages(other, "DEADLINE_REMINDER")).containsExactlyInAnyOrder(
                    "Due within 24 hours: At the boundary", "Due within 24 hours: Submitted",
                    "Due within 24 hours: Expired attempt quiz");
            assertThat(messages(suspended, "DEADLINE_REMINDER")).isEmpty();
            assertThat(messages(tutor, "DEADLINE_REMINDER")).isEmpty();
            db.update("UPDATE assignment SET deadline=? WHERE assignment_id=?", NOW.plusHours(23), due);
            maintenance.sendDeadlineReminders();
            assertThat(messages(student, "DEADLINE_REMINDER")).containsExactlyInAnyOrder(
                    "Due within 24 hours: At the boundary", "Due within 24 hours: At the boundary",
                    "Due within 24 hours: Unfinished quiz");
            status.setRollbackOnly();
        });
    }

    @Test
    void excludesNotificationsAtNinetyDaysAndCleanupPreservesTheSource() throws Exception {
        UUID root = discussionId(request("POST", "/classes/" + course + "/discussions", student,
                "{\"title\":\"Thread\",\"content\":\"Question\"}", 201));
        UUID reply = discussionId(request("POST", "/discussions/" + root + "/replies", tutor,
                "{\"content\":\"Answer\"}", 201));
        UUID expired = db.queryForObject("""
                UPDATE notification SET created_at = ? WHERE recipient_id=? AND source_event_key=?
                RETURNING notification_id
                """, UUID.class, NOW.minusDays(90), student, "reply:" + reply);
        UUID live = notice(student, NOW.minusDays(90).plusSeconds(1));
        var inbox = request("GET", INBOX, student, null, 200);
        assertThat(inbox.get("size").asInt()).isEqualTo(20);
        assertThat(inbox.get("total_items").asLong()).isEqualTo(1);
        assertThat(inbox.get("items").get(0).get("notification_id").asString()).isEqualTo(live.toString());
        assertThat(request("PATCH", INBOX + "/" + expired, student, MARK_READ, 404).get("code").asString())
                .isEqualTo("NOTIFICATION_NOT_FOUND");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            maintenance.deleteExpired();
            assertThat(db.queryForList("SELECT notification_id FROM notification WHERE recipient_id=?", UUID.class, student))
                    .containsExactly(live);
            assertThat(db.queryForObject("SELECT content FROM discussion WHERE discussion_id=?", String.class, reply))
                    .isEqualTo("Answer");
            status.setRollbackOnly();
        });
    }

    private UUID user(String role, String access) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO lms_user_access(user_id,full_name,email,lms_role,access_status) VALUES (?,'Fixture',?,?,?)",
                id, id + "@example.test", role, access);
        users.add(id);
        return id;
    }

    private UUID membership(UUID user) {
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO class_member(class_member_id,class_id,student_id,status,requested_at,joined_at)
                VALUES (?,?,?,'ACTIVE',?,?)
                """, id, course, user, NOW.minusDays(2), NOW.minusDays(1));
        return id;
    }

    private UUID notice(UUID user, OffsetDateTime createdAt) {
        return db.queryForObject("""
                INSERT INTO notification(recipient_id,notification_type,title,message,source_event_key,created_at)
                VALUES (?,'CONTENT_RELEASED','Notice','Body',?,?) RETURNING notification_id
                """, UUID.class, user, UUID.randomUUID().toString(), createdAt);
    }

    private UUID assignment(String title, String visibility, OffsetDateTime open, OffsetDateTime due) {
        UUID activity = activity(title, "ASSIGNMENT", visibility);
        return db.queryForObject("INSERT INTO assignment(activity_id,opening_time,deadline) VALUES (?,?,?) RETURNING assignment_id",
                UUID.class, activity, open, due);
    }

    private UUID quiz(String title, OffsetDateTime due) {
        UUID activity = activity(title, "QUIZ", "PUBLISHED");
        UUID id = db.queryForObject("INSERT INTO quiz(activity_id,time_limit_minutes,deadline) VALUES (?,20,?) RETURNING quiz_id",
                UUID.class, activity, due);
        UUID question = db.queryForObject("INSERT INTO question(quiz_id,question_type,content,points_awarded,order_index)"
                + " VALUES (?,'MCQ_SINGLE','Question',1,0) RETURNING question_id", UUID.class, id);
        db.update("INSERT INTO question_option(question_id,content,is_correct,order_index)"
                + " VALUES (?,'Correct',TRUE,0), (?,'Incorrect',FALSE,1)", question, question);
        return id;
    }

    private UUID activity(String title, String type, String visibility) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO activity(activity_id,lesson_id,title,activity_type,sequence_order,status) VALUES (?,?,?,?,0,?)",
                id, lesson, title, type, visibility);
        return id;
    }

    private List<String> titles(UUID user, String type) {
        return db.queryForList("SELECT title FROM notification WHERE recipient_id=? AND notification_type=?",
                String.class, user, type);
    }

    private List<String> messages(UUID user, String type) {
        return db.queryForList("SELECT message FROM notification WHERE recipient_id=? AND notification_type=?",
                String.class, user, type);
    }

    private UUID discussionId(JsonNode result) {
        return UUID.fromString(result.get("discussion_id").asString());
    }

    private JsonNode request(String method, String path, UUID actor, String body, int expected) throws Exception {
        return response(http.send(httpRequest(method, path, actor, body), HttpResponse.BodyHandlers.ofString()), expected);
    }

    private HttpRequest httpRequest(String method, String path, UUID actor, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(10));
        if (actor != null) {
            var claims = JWT.claims();
            claims.put("sub", actor.toString());
            claims.put("iat", NOW.minusMinutes(5).toEpochSecond());
            claims.put("exp", NOW.plusMinutes(5).toEpochSecond());
            builder.header("Authorization", "Bearer " + JWT.token(claims));
        }
        if (body != null) builder.header("Content-Type", "application/json");
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private JsonNode response(HttpResponse<String> response, int expected) throws Exception {
        assertThat(response.statusCode()).as("%s %s: %s", response.request().method(), response.uri(), response.body())
                .isEqualTo(expected);
        return expected == 204 ? null : json.readTree(response.body());
    }

    private static JwtTestSupport startJwt() {
        try { return new JwtTestSupport(); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}
