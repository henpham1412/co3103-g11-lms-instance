package vn.edu.hcmut.co3103.lms.controller.interaction;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Discussion contract over a real servlet container and the real PostgreSQL schema. Authorization,
 * authorship and the deletion constraints are only observable end to end here: a mocked service
 * would not prove that a denied caller stored nothing, or that a reply kept its parent's context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(DiscussionHttpTest.Clocks.class)
class DiscussionHttpTest {

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
    private UUID suspendedStudentId;
    private UUID classId;
    private UUID otherClassId;
    private UUID lessonId;
    private UUID secondLessonId;
    private UUID otherLessonId;
    private String tutorToken;
    private String otherTutorToken;
    private String studentToken;
    private String secondStudentToken;
    private String pendingToken;
    private String leftToken;
    private String outsiderToken;
    private String adminToken;
    private String suspendedToken;

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
        suspendedStudentId = insertUser("STUDENT", "SUSPENDED");

        tutorToken = tokenFor(tutorId);
        otherTutorToken = tokenFor(otherTutorId);
        studentToken = tokenFor(studentId);
        secondStudentToken = tokenFor(secondStudentId);
        pendingToken = tokenFor(pendingStudentId);
        leftToken = tokenFor(leftStudentId);
        outsiderToken = tokenFor(outsiderId);
        adminToken = tokenFor(adminId);
        suspendedToken = tokenFor(suspendedStudentId);

        classId = insertClass(tutorId);
        otherClassId = insertClass(otherTutorId);
        UUID chapterId = insertChapter(classId);
        lessonId = insertLesson(chapterId);
        secondLessonId = insertLesson(chapterId);
        otherLessonId = insertLesson(insertChapter(otherClassId));

        insertMember(classId, studentId, "ACTIVE");
        insertMember(classId, secondStudentId, "ACTIVE");
        insertMember(classId, pendingStudentId, "PENDING");
        insertMember(classId, leftStudentId, "INACTIVE");
        insertMember(classId, suspendedStudentId, "ACTIVE");
    }

    @AfterEach
    void cleanup() {
        for (UUID id : classIds) {
            db.update("DELETE FROM discussion WHERE class_id = ? AND parent_id IS NOT NULL", id);
            db.update("DELETE FROM discussion WHERE class_id = ?", id);
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
    void authorshipComesFromTheTokenAndASuppliedIdentityIsRefused() throws Exception {
        HttpResponse<String> injected = post("/classes/" + classId + "/discussions", studentToken,
                "{\"title\":\"Whose post\",\"content\":\"body\",\"author_id\":\"" + tutorId + "\"}");
        assertThat(injected.statusCode()).isEqualTo(400);
        assertThat(code(injected)).isEqualTo("VALIDATION_ERROR");
        assertThat(threadCount(classId)).isZero();

        HttpResponse<String> created = post("/classes/" + classId + "/discussions", studentToken,
                "{\"title\":\"Question about inheritance\",\"content\":\"Could someone explain this example?\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = json(created);
        UUID discussionId = UUID.fromString(body.get("discussion_id").asString());

        assertThat(body.get("author_id").asString()).isEqualTo(studentId.toString());
        assertThat(body.get("class_id").asString()).isEqualTo(classId.toString());
        assertThat(body.get("parent_id").isNull()).isTrue();
        assertThat(body.get("lesson_id").isNull()).isTrue();

        Map<String, Object> row = db.queryForMap("SELECT * FROM discussion WHERE discussion_id = ?", discussionId);
        assertThat(row.get("author_id")).isEqualTo(studentId);
        assertThat(row.get("class_id")).isEqualTo(classId);
        assertThat(row.get("parent_id")).isNull();
        assertThat(row.get("lesson_id")).isNull();
        assertThat(row.get("title")).isEqualTo("Question about inheritance");
        assertThat(row.get("content")).isEqualTo("Could someone explain this example?");
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void titleAndContentAreValidatedBeforeAnythingIsStored() throws Exception {
        String path = "/classes/" + classId + "/discussions";
        assertThat(code(post(path, studentToken, "{\"title\":\"  \",\"content\":\"body\"}")))
                .isEqualTo("VALIDATION_ERROR");
        assertThat(code(post(path, studentToken, "{\"content\":\"body\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(post(path, studentToken, "{\"title\":\"T\",\"content\":\"   \"}")))
                .isEqualTo("VALIDATION_ERROR");
        assertThat(code(post(path, studentToken, "{\"title\":\"T\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(post(path, studentToken,
                "{\"title\":\"" + "x".repeat(256) + "\",\"content\":\"body\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(post(path, studentToken, "{\"title\":\"T\",\"content\":123}")))
                .isEqualTo("VALIDATION_ERROR");
        assertThat(threadCount(classId)).isZero();

        HttpResponse<String> atLimit = post(path, studentToken,
                "{\"title\":\"" + "x".repeat(255) + "\",\"content\":\"body\"}");
        assertThat(atLimit.statusCode()).isEqualTo(201);
        assertThat(json(atLimit).get("title").asString()).hasSize(255);
        assertThat(threadCount(classId)).isEqualTo(1);
    }

    @Test
    void onlyActiveMembersAndTheOwningTutorMayParticipate() throws Exception {
        Map<String, String> denied = new LinkedHashMap<>();
        denied.put("non-member student", outsiderToken);
        denied.put("pending applicant", pendingToken);
        denied.put("student who left", leftToken);
        denied.put("tutor of another class", otherTutorToken);
        denied.put("administrator", adminToken);
        denied.put("suspended local access", suspendedToken);

        for (Map.Entry<String, String> actor : denied.entrySet()) {
            HttpResponse<String> response = post("/classes/" + classId + "/discussions", actor.getValue(),
                    "{\"title\":\"Denied\",\"content\":\"body\"}");
            assertThat(response.statusCode()).as("create as %s", actor.getKey()).isEqualTo(403);
            assertThat(code(response)).as("create as %s", actor.getKey()).isEqualTo("FORBIDDEN");
            assertThat(get("/classes/" + classId + "/discussions", actor.getValue()).statusCode())
                    .as("list as %s", actor.getKey()).isEqualTo(403);
        }
        assertThat(threadCount(classId)).isZero();

        assertThat(post("/classes/" + classId + "/discussions", studentToken,
                "{\"title\":\"Student\",\"content\":\"body\"}").statusCode()).isEqualTo(201);
        assertThat(post("/classes/" + classId + "/discussions", tutorToken,
                "{\"title\":\"Tutor\",\"content\":\"body\"}").statusCode()).isEqualTo(201);
        assertThat(threadCount(classId)).isEqualTo(2);
    }

    @Test
    void aMissingTokenIsUnauthorizedAndTheEnvelopeIsTheDocumentedOne() throws Exception {
        HttpResponse<String> anonymous = request("GET", "/classes/" + classId + "/discussions", null, null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(code(anonymous)).isEqualTo("UNAUTHORIZED");

        HttpResponse<String> unknown = get("/discussions/" + UUID.randomUUID(), studentToken);
        assertThat(unknown.statusCode()).isEqualTo(404);
        JsonNode envelope = json(unknown);
        assertThat(envelope.get("code").asString()).isEqualTo("DISCUSSION_NOT_FOUND");
        assertThat(envelope.get("message").asString()).isNotBlank();
        assertThat(envelope.get("details").isArray()).isTrue();
        assertThat(envelope.get("details")).isEmpty();
    }

    @Test
    void aReplyInheritsItsParentContextAndCannotCarryItsOwn() throws Exception {
        UUID threadId = createThread(studentToken, "{\"lesson_id\":\"" + lessonId
                + "\",\"title\":\"Lesson question\",\"content\":\"body\"}");

        HttpResponse<String> injected = post("/discussions/" + threadId + "/replies", studentToken,
                "{\"content\":\"reply\",\"lesson_id\":\"" + secondLessonId + "\"}");
        assertThat(injected.statusCode()).isEqualTo(400);
        assertThat(code(injected)).isEqualTo("VALIDATION_ERROR");

        for (String nonTextContent : List.of("123", "true", "{\"text\":\"reply\"}")) {
            HttpResponse<String> coerced = post("/discussions/" + threadId + "/replies", studentToken,
                    "{\"content\":" + nonTextContent + "}");
            assertThat(coerced.statusCode()).as("reply content %s", nonTextContent).isEqualTo(400);
            assertThat(code(coerced)).as("reply content %s", nonTextContent).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(code(post("/discussions/" + threadId + "/replies", studentToken, "{\"content\":\"  \"}")))
                .isEqualTo("VALIDATION_ERROR");
        assertThat(db.queryForObject("SELECT count(*) FROM discussion WHERE parent_id = ?", Long.class, threadId))
                .isZero();

        HttpResponse<String> reply = post("/discussions/" + threadId + "/replies", secondStudentToken,
                "{\"content\":\"First reply\"}");
        assertThat(reply.statusCode()).isEqualTo(201);
        JsonNode replyBody = json(reply);
        UUID replyId = UUID.fromString(replyBody.get("discussion_id").asString());
        assertThat(replyBody.get("class_id").asString()).isEqualTo(classId.toString());
        assertThat(replyBody.get("lesson_id").asString()).isEqualTo(lessonId.toString());
        assertThat(replyBody.get("parent_id").asString()).isEqualTo(threadId.toString());
        assertThat(replyBody.get("author_id").asString()).isEqualTo(secondStudentId.toString());
        assertThat(replyBody.get("title").isNull()).isTrue();

        HttpResponse<String> nested = post("/discussions/" + replyId + "/replies", studentToken,
                "{\"content\":\"Reply to a reply\"}");
        assertThat(nested.statusCode()).isEqualTo(201);
        assertThat(json(nested).get("parent_id").asString()).isEqualTo(replyId.toString());
        assertThat(json(nested).get("lesson_id").asString()).isEqualTo(lessonId.toString());

        assertThat(post("/discussions/" + UUID.randomUUID() + "/replies", studentToken,
                "{\"content\":\"orphan\"}").statusCode()).isEqualTo(404);
        assertThat(db.queryForObject("SELECT count(*) FROM discussion WHERE class_id = ?", Long.class, classId))
                .isEqualTo(3L);
    }

    @Test
    void aLessonOutsideTheClassOrUnknownIsRejected() throws Exception {
        String path = "/classes/" + classId + "/discussions";
        assertThat(code(post(path, studentToken, "{\"lesson_id\":\"" + otherLessonId
                + "\",\"title\":\"Foreign lesson\",\"content\":\"body\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(post(path, studentToken, "{\"lesson_id\":\"" + UUID.randomUUID()
                + "\",\"title\":\"Unknown lesson\",\"content\":\"body\"}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(post(path, studentToken, "{\"lesson_id\":\"not-a-uuid\",\"title\":\"T\",\"content\":\"b\"}")))
                .isEqualTo("VALIDATION_ERROR");
        assertThat(threadCount(classId)).isZero();

        HttpResponse<String> general = post(path, studentToken, "{\"title\":\"General\",\"content\":\"body\"}");
        assertThat(general.statusCode()).isEqualTo(201);
        assertThat(json(general).get("lesson_id").isNull()).isTrue();
    }

    @Test
    void threadsAreOrderedByTheNewestPostAnywhereInTheThread() throws Exception {
        UUID older = createThread(studentToken, "{\"title\":\"Older\",\"content\":\"body\"}");
        UUID newer = createThread(studentToken, "{\"title\":\"Newer\",\"content\":\"body\"}");

        assertThat(rootIds(json(listThreads(studentToken, "?page=1&size=20"))))
                .containsExactly(newer, older);

        UUID reply = createReply(older, studentToken, "{\"content\":\"bump\"}");
        assertThat(rootIds(json(listThreads(studentToken, "?page=1&size=20"))))
                .containsExactly(older, newer);

        createReply(reply, studentToken, "{\"content\":\"nested bump\"}");
        assertThat(rootIds(json(listThreads(studentToken, "?page=1&size=20"))))
                .containsExactly(older, newer);

        JsonNode page = json(listThreads(studentToken, "?page=1&size=1"));
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(1);
        assertThat(page.get("total_items").asLong()).isEqualTo(2L);
        assertThat(page.get("total_pages").asInt()).isEqualTo(2);
        assertThat(rootIds(page)).containsExactly(older);
        assertThat(rootIds(json(listThreads(studentToken, "?page=2&size=1")))).containsExactly(newer);
        assertThat(rootIds(json(listThreads(studentToken, "?page=3&size=1")))).isEmpty();
    }

    @Test
    void theLessonFilterOnlyReturnsThreadsOfThatLesson() throws Exception {
        UUID onLesson = createThread(studentToken, "{\"lesson_id\":\"" + lessonId
                + "\",\"title\":\"On lesson\",\"content\":\"body\"}");
        createThread(studentToken, "{\"lesson_id\":\"" + secondLessonId
                + "\",\"title\":\"On another lesson\",\"content\":\"body\"}");
        createThread(studentToken, "{\"title\":\"General\",\"content\":\"body\"}");

        assertThat(rootIds(json(listThreads(studentToken, "?lesson_id=" + lessonId))))
                .containsExactly(onLesson);
        assertThat(json(listThreads(studentToken, "?lesson_id=" + lessonId)).get("total_items").asLong())
                .isEqualTo(1L);
        assertThat(json(listThreads(studentToken, "")).get("total_items").asLong()).isEqualTo(3L);
        assertThat(get("/classes/" + classId + "/discussions?lesson_id=" + otherLessonId, studentToken).statusCode())
                .isEqualTo(400);
    }

    @Test
    void editingIsLimitedToTheAuthorOrOwningTutorAndToTitleAndContent() throws Exception {
        UUID threadId = createThread(studentToken, "{\"title\":\"Original\",\"content\":\"original body\"}");
        String path = "/discussions/" + threadId;

        HttpResponse<String> foreign = patch(path, secondStudentToken, "{\"content\":\"hijacked\"}");
        assertThat(foreign.statusCode()).isEqualTo(403);
        assertThat(code(foreign)).isEqualTo("FORBIDDEN");
        assertThat(patch(path, otherTutorToken, "{\"content\":\"hijacked\"}").statusCode()).isEqualTo(403);
        assertThat(content(threadId)).isEqualTo("original body");

        for (String forbiddenField : List.of("author_id", "class_id", "lesson_id", "parent_id")) {
            HttpResponse<String> response = patch(path, studentToken,
                    "{\"" + forbiddenField + "\":\"" + UUID.randomUUID() + "\"}");
            assertThat(response.statusCode()).as("patch %s", forbiddenField).isEqualTo(400);
            assertThat(code(response)).as("patch %s", forbiddenField).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(patch(path, studentToken, "{}").statusCode()).isEqualTo(400);
        assertThat(patch(path, studentToken, "{\"title\":null}").statusCode()).isEqualTo(400);
        assertThat(patch(path, studentToken, "{\"content\":\"  \"}").statusCode()).isEqualTo(400);
        assertThat(code(patch(path, studentToken, "{\"content\":123}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(code(patch(path, studentToken, "{\"title\":true}"))).isEqualTo("VALIDATION_ERROR");
        assertThat(db.queryForMap("SELECT * FROM discussion WHERE discussion_id = ?", threadId).get("author_id"))
                .isEqualTo(studentId);

        HttpResponse<String> edited = patch(path, studentToken, "{\"content\":\"edited body\"}");
        assertThat(edited.statusCode()).isEqualTo(200);
        assertThat(json(edited).get("content").asString()).isEqualTo("edited body");
        assertThat(content(threadId)).isEqualTo("edited body");
        assertThat(db.queryForObject("SELECT updated_at > created_at FROM discussion WHERE discussion_id = ?",
                Boolean.class, threadId)).isTrue();

        assertThat(patch(path, tutorToken, "{\"title\":\"Moderated title\"}").statusCode()).isEqualTo(200);
        assertThat(db.queryForMap("SELECT * FROM discussion WHERE discussion_id = ?", threadId).get("title"))
                .isEqualTo("Moderated title");
    }

    @Test
    void deletionIsPhysicalAndBlockedWhileAReplyDependsOnIt() throws Exception {
        UUID threadId = createThread(studentToken, "{\"title\":\"Thread\",\"content\":\"body\"}");
        UUID replyId = createReply(threadId, secondStudentToken, "{\"content\":\"reply\"}");
        String threadPath = "/discussions/" + threadId;

        assertThat(delete(threadPath, secondStudentToken).statusCode()).isEqualTo(403);
        HttpResponse<String> blocked = delete(threadPath, studentToken);
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(code(blocked)).isEqualTo("DISCUSSION_HAS_REPLIES");
        assertThat(threadCount(classId)).isEqualTo(2);

        assertThat(delete("/discussions/" + replyId, secondStudentToken).statusCode()).isEqualTo(204);
        assertThat(db.queryForObject("SELECT count(*) FROM discussion WHERE discussion_id = ?",
                Long.class, replyId)).isZero();

        assertThat(delete(threadPath, studentToken).statusCode()).isEqualTo(204);
        assertThat(db.queryForObject("SELECT count(*) FROM discussion WHERE discussion_id = ?",
                Long.class, threadId)).isZero();
        assertThat(delete(threadPath, studentToken).statusCode()).isEqualTo(404);

        UUID moderated = createThread(studentToken, "{\"title\":\"Moderated\",\"content\":\"body\"}");
        assertThat(delete("/discussions/" + moderated, tutorToken).statusCode()).isEqualTo(204);
    }

    @Test
    void anArchivedClassKeepsReadingButRefusesEveryDiscussionWrite() throws Exception {
        UUID threadId = createThread(studentToken, "{\"title\":\"Before archive\",\"content\":\"body\"}");
        db.update("UPDATE class SET status = 'ARCHIVED' WHERE class_id = ?", classId);

        assertThat(listThreads(studentToken, "").statusCode()).isEqualTo(200);
        assertThat(get("/discussions/" + threadId, studentToken).statusCode()).isEqualTo(200);

        assertThat(code(post("/classes/" + classId + "/discussions", studentToken,
                "{\"title\":\"After archive\",\"content\":\"body\"}"))).isEqualTo("CLASS_ARCHIVED");
        assertThat(post("/classes/" + classId + "/discussions", studentToken,
                "{\"title\":\"After archive\",\"content\":\"body\"}").statusCode()).isEqualTo(409);
        assertThat(post("/discussions/" + threadId + "/replies", studentToken,
                "{\"content\":\"reply\"}").statusCode()).isEqualTo(409);
        assertThat(patch("/discussions/" + threadId, studentToken, "{\"content\":\"edit\"}").statusCode())
                .isEqualTo(409);
        assertThat(delete("/discussions/" + threadId, studentToken).statusCode()).isEqualTo(409);
        assertThat(threadCount(classId)).isEqualTo(1);
        assertThat(content(threadId)).isEqualTo("body");
    }

    @Test
    void paginationParametersAreValidated() throws Exception {
        for (String query : List.of("?page=0", "?page=-1", "?size=0", "?size=101", "?page=abc", "?size=1.5")) {
            HttpResponse<String> response = listThreads(studentToken, query);
            assertThat(response.statusCode()).as("query %s", query).isEqualTo(400);
            assertThat(code(response)).as("query %s", query).isEqualTo("VALIDATION_ERROR");
        }
        assertThat(listThreads(studentToken, "?page=1&size=100").statusCode()).isEqualTo(200);
    }

    @Test
    void aDiscussionOfAnotherClassIsNotReadableByItsMember() throws Exception {
        UUID foreignThread = createThread(otherTutorToken, otherClassId,
                "{\"title\":\"Other class\",\"content\":\"body\"}");
        HttpResponse<String> response = get("/discussions/" + foreignThread, studentToken);
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(code(response)).isEqualTo("FORBIDDEN");
        assertThat(get("/classes/" + otherClassId + "/discussions", studentToken).statusCode()).isEqualTo(403);
    }

    @Test
    void aLessonWithDiscussionsIsNoLongerPhysicallyDeletable() throws Exception {
        createThread(studentToken, "{\"lesson_id\":\"" + lessonId + "\",\"title\":\"Pinned\",\"content\":\"body\"}");

        HttpResponse<String> response = delete("/lessons/" + lessonId, tutorToken);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(code(response)).isEqualTo("CONTENT_HAS_DEPENDENT_LEARNING_DATA");
        assertThat(db.queryForObject("SELECT count(*) FROM lesson WHERE lesson_id = ?", Long.class, lessonId))
                .isEqualTo(1L);
    }

    private UUID createThread(String token, String body) throws Exception {
        return createThread(token, classId, body);
    }

    private UUID createThread(String token, UUID targetClassId, String body) throws Exception {
        HttpResponse<String> response = post("/classes/" + targetClassId + "/discussions", token, body);
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(json(response).get("discussion_id").asString());
    }

    private UUID createReply(UUID parentId, String token, String body) throws Exception {
        HttpResponse<String> response = post("/discussions/" + parentId + "/replies", token, body);
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(json(response).get("discussion_id").asString());
    }

    private HttpResponse<String> listThreads(String token, String query) throws Exception {
        return get("/classes/" + classId + "/discussions" + query, token);
    }

    private List<UUID> rootIds(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("items").forEach(item -> ids.add(UUID.fromString(item.get("discussion_id").asString())));
        return ids;
    }

    private long threadCount(UUID targetClassId) {
        return db.queryForObject("SELECT count(*) FROM discussion WHERE class_id = ?", Long.class, targetClassId);
    }

    private String content(UUID discussionId) {
        return db.queryForObject("SELECT content FROM discussion WHERE discussion_id = ?", String.class, discussionId);
    }

    private String code(HttpResponse<String> response) {
        return json(response).get("code").asString();
    }

    private UUID insertUser(String role, String accessStatus) {
        UUID userId = UUID.randomUUID();
        userIds.add(userId);
        db.update("INSERT INTO lms_user_access(user_id, full_name, email, lms_role, access_status) "
                        + "VALUES (?, ?, ?, ?, ?)",
                userId, "Discussion " + userId, userId + "@example.test", role, accessStatus);
        return userId;
    }

    private UUID insertClass(UUID ownerTutorId) {
        UUID id = UUID.randomUUID();
        classIds.add(id);
        db.update("INSERT INTO class(class_id, tutor_id, class_code, title, enrollment_policy, status) "
                        + "VALUES (?, ?, ?, 'Discussion Test Class', 'OPEN', 'ACTIVE')",
                id, ownerTutorId, "D" + UUID.randomUUID().toString().substring(0, 8));
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