package vn.edu.hcmut.co3103.lms.controller.assessment;

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
import vn.edu.hcmut.co3103.lms.support.MaterialFiles;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Actual HTTP/security, canonical content guards, multipart bytes and PostgreSQL transactions. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AssignmentPostgresHttpTest.Clocks.class)
class AssignmentPostgresHttpTest {
    static final JwtTestSupport JWT = jwt();
    static final Path STORAGE = temporaryStorage();
    static Path temporaryStorage() { try { return Files.createTempDirectory("lms6-http-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    static JwtTestSupport jwt() { try { return new JwtTestSupport(); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @TestConfiguration(proxyBeanMethods = false) static class Clocks { @Bean @Primary Clock clock() { return JwtTestSupport.CLOCK; } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> JwtTestSupport.ISSUER);
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwksUri);
        r.add("lms.storage.assignments.directory", STORAGE::toString);
        r.add("lms.quiz.expiry-scan-ms", () -> "3600000");
    }
    @AfterAll static void close() throws Exception {
        JWT.close();
        try (var paths = Files.walk(STORAGE)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    final HttpClient http = HttpClient.newHttpClient();
    UUID owner, otherTutor, student, outsider, course, foreign, member, chapter, lesson, otherLesson;
    @BeforeEach void seed() {
        owner = UUID.randomUUID(); otherTutor = UUID.randomUUID(); student = UUID.randomUUID(); outsider = UUID.randomUUID();
        for (UUID user : List.of(owner, otherTutor, student, outsider)) db.update("INSERT INTO lms_user_access(user_id,full_name,email,lms_role,access_status) VALUES (?,?,?,?,'ACTIVE')", user, "LMS6 user", user + "@example.test", user.equals(owner) || user.equals(otherTutor) ? "TUTOR" : "STUDENT");
        course = UUID.randomUUID(); foreign = UUID.randomUUID(); member = UUID.randomUUID(); chapter = UUID.randomUUID(); lesson = UUID.randomUUID(); otherLesson = UUID.randomUUID();
        for (UUID c : List.of(course, foreign)) db.update("INSERT INTO class(class_id,tutor_id,class_code,title,enrollment_policy,status) VALUES (?,?,?,'Assignments','OPEN','ACTIVE')", c, c.equals(course) ? owner : otherTutor, "A" + c.toString().substring(0, 8));
        db.update("INSERT INTO class_member(class_member_id,class_id,student_id,status,joined_at) VALUES (?,?,?,'ACTIVE',CURRENT_TIMESTAMP)", member, course, student);
        db.update("INSERT INTO chapter(chapter_id,class_id,title,sequence_order,status) VALUES (?,?,'Chapter',0,'PUBLISHED')", chapter, course);
        db.update("INSERT INTO lesson(lesson_id,chapter_id,title,sequence_order,status) VALUES (?,?,'Lesson',0,'PUBLISHED')", lesson, chapter);
        UUID foreignChapter = UUID.randomUUID();
        db.update("INSERT INTO chapter(chapter_id,class_id,title,sequence_order,status) VALUES (?,?,'Foreign',0,'PUBLISHED')", foreignChapter, foreign);
        db.update("INSERT INTO lesson(lesson_id,chapter_id,title,sequence_order,status) VALUES (?,?,'Foreign',0,'PUBLISHED')", otherLesson, foreignChapter);
    }
    @AfterEach void cleanupOwnFixtures() throws Exception {
        for (UUID c : List.of(course, foreign)) {
            db.update("DELETE FROM submission WHERE assignment_id IN (SELECT assignment_id FROM assignment WHERE activity_id IN (SELECT a.activity_id FROM activity a JOIN lesson l ON l.lesson_id=a.lesson_id JOIN chapter ch ON ch.chapter_id=l.chapter_id WHERE ch.class_id=?))", c);
            db.update("DELETE FROM assignment WHERE activity_id IN (SELECT a.activity_id FROM activity a JOIN lesson l ON l.lesson_id=a.lesson_id JOIN chapter ch ON ch.chapter_id=l.chapter_id WHERE ch.class_id=?)", c);
            db.update("DELETE FROM activity WHERE lesson_id IN (SELECT l.lesson_id FROM lesson l JOIN chapter ch ON ch.chapter_id=l.chapter_id WHERE ch.class_id=?)", c);
            db.update("DELETE FROM lesson WHERE chapter_id IN (SELECT chapter_id FROM chapter WHERE class_id=?)", c);
            db.update("DELETE FROM chapter WHERE class_id=?", c); db.update("DELETE FROM class_member WHERE class_id=?", c); db.update("DELETE FROM class WHERE class_id=?", c);
        }
        for (UUID user : List.of(owner, otherTutor, student, outsider)) db.update("DELETE FROM lms_user_access WHERE user_id=?", user);
        try (var paths = Files.list(STORAGE)) { for (Path p : paths.toList()) Files.deleteIfExists(p); }
    }
    @Test void createsActivityAndConfigurationAtomicallyThroughTheDocumentedRoute() throws Exception {
        UUID activity = create(); var configuration = checked("GET", configPath(activity), null, student, 200);
        assertThat(configuration.get("activity_id").asText()).isEqualTo(activity.toString());
        assertThat(configuration.get("allow_late").asBoolean()).isFalse();
        assertThat(configuration.get("allowed_file_types").get(0).asText()).isEqualTo("pdf");
        assertThat(db.queryForObject("SELECT count(*) FROM assignment WHERE activity_id=?", Integer.class, activity)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM activity WHERE activity_id=?", String.class, activity)).isEqualTo("PUBLISHED");
    }
    @Test void invalidAssignmentRollsBackItsActivityAndCannotLeaveABareAssignment() throws Exception {
        checked("POST", activityPath(), creation("{\"deadline\":\"" + future() + "\",\"max_file_size_mb\":0}"), owner, 400);
        checked("POST", activityPath(), "{\"title\":\"Bare\",\"activity_type\":\"ASSIGNMENT\"}", owner, 409);
        assertThat(db.queryForObject("SELECT count(*) FROM activity WHERE lesson_id=?", Integer.class, lesson)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM assignment a JOIN activity x ON x.activity_id=a.activity_id WHERE x.lesson_id=?", Integer.class, lesson)).isZero();
    }
    @Test void persistenceFailureAlsoRollsBackTheActivityInsert() throws Exception {
        String name = "reject_assignment_" + course.toString().replace("-", "");
        db.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF EXISTS(SELECT 1 FROM activity a WHERE a.activity_id=NEW.activity_id AND a.lesson_id='" + lesson + "'::uuid) THEN RAISE EXCEPTION 'fixture rejection'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON assignment FOR EACH ROW EXECUTE FUNCTION " + name + "()");
        try {
            checked("POST", activityPath(), creation(defaultConfiguration()), owner, 500);
            assertThat(db.queryForObject("SELECT count(*) FROM activity WHERE lesson_id=?", Integer.class, lesson)).isZero();
        } finally { db.execute("DROP TRIGGER " + name + " ON assignment"); db.execute("DROP FUNCTION " + name + "()"); }
    }
    @Test void nonAssignmentCreationRetainsItsExistingPlacementContract() throws Exception {
        var body = checked("POST", activityPath(), "{\"title\":\"Quiz\",\"activity_type\":\"QUIZ\"}", owner, 201);
        assertThat(body.get("activity_type").asText()).isEqualTo("QUIZ");
        assertThat(db.queryForObject("SELECT count(*) FROM assignment WHERE activity_id=?", Integer.class, UUID.fromString(body.get("activity_id").asText()))).isZero();
        checked("POST", activityPath(), "{\"title\":\"Mismatch\",\"activity_type\":\"QUIZ\",\"assignment\":" + defaultConfiguration() + "}", owner, 409);
    }
    @Test void creationRequiresActiveLocalTutorRoleAndActualOwnership() throws Exception {
        checked("POST", activityPath(), creation(defaultConfiguration()), student, 403);
        checked("POST", activityPath(), creation(defaultConfiguration()), otherTutor, 403);
        db.update("UPDATE lms_user_access SET access_status='SUSPENDED' WHERE user_id=?", owner);
        checked("POST", activityPath(), creation(defaultConfiguration()), owner, 403);
        assertThat(db.queryForObject("SELECT count(*) FROM activity WHERE lesson_id=?", Integer.class, lesson)).isZero();
    }
    @Test void patchPreservesOmittedSettingsAndRemovesTheContradictoryPostPutRoutes() throws Exception {
        UUID activity = create(); var before = checked("GET", configPath(activity), null, owner, 200);
        var after = checked("PATCH", configPath(activity), "{\"allow_late\":true}", owner, 200);
        for (String field : List.of("deadline", "opening_time", "allowed_file_types", "max_file_size_mb")) assertThat(after.get(field)).as(field).isEqualTo(before.get(field));
        assertThat(after.get("allow_late").asBoolean()).isTrue();
        checked("POST", configPath(activity), defaultConfiguration(), owner, 405);
        checked("PUT", configPath(activity), defaultConfiguration(), owner, 405);
        checked("PATCH", configPath(activity), "{\"allow_late\":false}", student, 403);
        checked("PATCH", configPath(activity), "{\"allow_late\":false}", otherTutor, 403);
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"title\":\"Unexpected\"}", "{\"max_file_size_mb\":0}", "{\"allow_late\":null}", "{\"allow_late\":\"true\"}", "{\"max_file_size_mb\":1.5}", "{\"deadline\":null}", "{\"allowed_file_types\":[\"exe\"]}", "{\"allowed_file_types\":[\"\"]}", "{\"allowed_file_types\":[\"pdf,txt\"]}"})
    void invalidPatchDoesNotChangeExistingConfiguration(String patch) throws Exception {
        UUID activity = create(); var before = checked("GET", configPath(activity), null, owner, 200);
        checked("PATCH", configPath(activity), patch, owner, 400);
        assertThat(checked("GET", configPath(activity), null, owner, 200)).isEqualTo(before);
    }
    @Test void patchValidatesTheMergedWindowAndHonorsExplicitNullableFields() throws Exception {
        UUID activity = create();
        checked("PATCH", configPath(activity), "{\"opening_time\":\"" + OffsetDateTime.now().plusDays(3) + "\"}", owner, 400);
        var result = checked("PATCH", configPath(activity), "{\"opening_time\":null,\"allowed_file_types\":null,\"max_file_size_mb\":null}", owner, 200);
        assertThat(result.get("allowed_file_types").isEmpty()).isTrue();
        assertThat(db.queryForObject("SELECT opening_time IS NULL AND allowed_file_types IS NULL AND max_file_size_mb IS NULL FROM assignment WHERE activity_id=?", Boolean.class, activity)).isTrue();
    }
    @ParameterizedTest @ValueSource(strings = {"activity", "lesson", "chapter"})
    void canonicalHiddenHierarchyDeniesStudentConfigurationAttachmentsAndNewWork(String table) throws Exception {
        UUID activity = create(); UUID assignment = assignment(activity);
        UUID attachment = UUID.fromString(json.readTree(upload("POST", "/assignments/" + assignment + "/attachments", "reference.pdf", "application/pdf", MaterialFiles.read("valid.pdf"), owner).body()).get("attachments").get(0).get("attachment_id").asText());
        UUID id = table.equals("activity") ? activity : table.equals("lesson") ? lesson : chapter;
        db.update("UPDATE " + table + " SET status='HIDDEN' WHERE " + table + "_id=?", id);
        var error = checked("GET", configPath(activity), null, student, 404);
        assertThat(error.get("code").asText()).isEqualTo("ACTIVITY_NOT_FOUND");
        checked("GET", "/assignments/" + assignment + "/attachments/" + attachment + "/file", null, student, 404);
        assertThat(upload("PUT", submitPath(assignment), "work.txt", "text/plain", "work".getBytes(), student).statusCode()).isEqualTo(404);
        checked("GET", configPath(activity), null, owner, 200);
        checked("PATCH", configPath(activity), "{\"allow_late\":true}", owner, 200);
    }
    @Test void archivedClassRejectsNewWorkButRetainsAuthorizedHistoricalReads() throws Exception {
        UUID activity = create(); UUID assignment = assignment(activity); var submitted = submit(assignment);
        db.update("UPDATE class SET status='ARCHIVED' WHERE class_id=?", course);
        var failed = upload("PUT", submitPath(assignment), "work.txt", "text/plain", "new".getBytes(), student);
        assertThat(failed.statusCode()).isEqualTo(409); assertThat(json.readTree(failed.body()).get("code").asText()).isEqualTo("CLASS_ARCHIVED");
        checked("GET", configPath(activity), null, owner, 200);
        checked("GET", "/assignments/" + assignment + "/submissions/me", null, student, 200);
        checked("GET", "/submissions/" + submitted.get("submission_id").asText(), null, student, 200);
        assertThat(request("GET", "/submissions/" + submitted.get("submission_id").asText() + "/file", null, student).statusCode()).isEqualTo(200);
        checked("PATCH", configPath(activity), "{\"allow_late\":true}", owner, 409);
    }
    @Test void foreignClassesNonMembersWrongOwnersAndInactiveMembershipsAreDenied() throws Exception {
        UUID activity = create(); UUID assignment = assignment(activity); var submitted = submit(assignment);
        checked("GET", configPath(activity), null, outsider, 403);
        checked("GET", configPath(activity), null, otherTutor, 403);
        checked("GET", "/submissions/" + submitted.get("submission_id").asText(), null, outsider, 403);
        var other = checked("POST", "/lessons/" + otherLesson + "/activities", creation(defaultConfiguration()), otherTutor, 201);
        checked("GET", configPath(UUID.fromString(other.get("activity_id").asText())), null, student, 403);
        db.update("UPDATE class_member SET status='INACTIVE' WHERE class_member_id=?", member);
        checked("GET", "/assignments/" + assignment + "/submissions/me", null, student, 403);
        assertThat(upload("PUT", submitPath(assignment), "work.txt", "text/plain", "new".getBytes(), student).statusCode()).isEqualTo(403);
        db.update("UPDATE class_member SET status='ACTIVE' WHERE class_member_id=?", member);
        assertThat(checked("GET", "/assignments/" + assignment + "/submissions/me", null, student, 200).get("submission_id")).isEqualTo(submitted.get("submission_id"));
    }
    @Test void invalidFileOrReplacementLeavesNoOrphanAndPreservesTheCurrentSubmission() throws Exception {
        UUID assignment = assignment(create());
        assertThat(upload("PUT", submitPath(assignment), "answer.pdf", "application/pdf", "not a pdf".getBytes(), student).statusCode()).isEqualTo(415);
        assertThat(db.queryForObject("SELECT count(*) FROM submission WHERE assignment_id=?", Integer.class, assignment)).isZero();
        try (var files = Files.list(STORAGE)) { assertThat(files.count()).isZero(); }
        var before = submit(assignment); long version = before.get("version").asLong();
        assertThat(upload("PUT", submitPath(assignment), "answer.pdf", "application/pdf", "still not pdf".getBytes(), student).statusCode()).isEqualTo(415);
        var after = checked("GET", "/assignments/" + assignment + "/submissions/me", null, student, 200);
        assertThat(after.get("submission_id")).isEqualTo(before.get("submission_id")); assertThat(after.get("version").asLong()).isEqualTo(version);
        try (var files = Files.list(STORAGE)) { assertThat(files.count()).isEqualTo(1); }
    }
    @Test void gradeAndRegradeReturnTheCommittedVersionAndRejectAnActuallyStaleClient() throws Exception {
        var submitted = submit(assignment(create())); String id = submitted.get("submission_id").asText();
        var first = checked("PATCH", "/submissions/" + id + "/grade", grade(55, submitted.get("version").asLong()), owner, 200);
        assertVersion(id, first); assertThat(first.get("status").asText()).isEqualTo("GRADED");
        var second = checked("PATCH", "/submissions/" + id + "/grade", grade(75, first.get("version").asLong()), owner, 200);
        assertVersion(id, second); assertThat(second.get("version").asLong()).isGreaterThan(first.get("version").asLong());
        var conflict = checked("PATCH", "/submissions/" + id + "/grade", grade(10, first.get("version").asLong()), owner, 409);
        assertThat(conflict.get("code").asText()).isEqualTo("SUBMISSION_STATE_CHANGED");
        assertThat(db.queryForObject("SELECT score FROM submission WHERE submission_id=?", Integer.class, UUID.fromString(id))).isEqualTo(75);
        checked("PATCH", "/submissions/" + id + "/grade", grade(10, second.get("version").asLong()), otherTutor, 403);
        checked("PATCH", "/submissions/" + id + "/grade", grade(10, second.get("version").asLong()), student, 403);
    }
    @Test void concurrentGradesWithTheSameVersionCannotSilentlyOverwriteEachOther() throws Exception {
        var submitted = submit(assignment(create())); String id = submitted.get("submission_id").asText(); long version = submitted.get("version").asLong();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
            var calls = new ArrayList<Future<HttpResponse<String>>>();
            for (int score : List.of(60, 70)) calls.add(pool.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout"); return request("PATCH", "/submissions/" + id + "/grade", grade(score, version), owner); }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            var responses = List.of(calls.get(0).get(20, TimeUnit.SECONDS), calls.get(1).get(20, TimeUnit.SECONDS));
            assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 409);
            var success = json.readTree(responses.stream().filter(r -> r.statusCode() == 200).findFirst().orElseThrow().body());
            assertVersion(id, success);
            assertThat(db.queryForObject("SELECT score FROM submission WHERE submission_id=?", Integer.class, UUID.fromString(id))).isEqualTo(success.get("score").asInt());
        }
    }
    @Test void replacingUngradedWorkDeletesOnlyTheOldObjectAfterCommitAndGradedWorkIsLocked() throws Exception {
        UUID assignment = assignment(create()); var before = submit(assignment); String id = before.get("submission_id").asText();
        String old = db.queryForObject("SELECT storage_path FROM submission WHERE submission_id=?", String.class, UUID.fromString(id));
        var replace = upload("PUT", submitPath(assignment), "updated.txt", "application/pdf", "updated text".getBytes(), student);
        assertThat(replace.statusCode()).isEqualTo(200);
        assertThat(Files.exists(STORAGE.resolve(old))).isFalse();
        var current = json.readTree(replace.body()); assertThat(current.get("version").asLong()).isGreaterThan(before.get("version").asLong());
        checked("PATCH", "/submissions/" + id + "/grade", grade(90, current.get("version").asLong()), owner, 200);
        assertThat(upload("PUT", submitPath(assignment), "again.txt", "text/plain", "again".getBytes(), student).statusCode()).isEqualTo(409);
        try (var files = Files.list(STORAGE)) { assertThat(files.count()).isEqualTo(1); }
    }
    @Test void deadlineOpeningAndExplicitLatePolicyAreEnforcedWithoutChangingOmittedDeadline() throws Exception {
        UUID activity = create(); UUID assignment = assignment(activity);
        db.update("UPDATE assignment SET opening_time=?,deadline=? WHERE assignment_id=?", OffsetDateTime.now().plusHours(1), OffsetDateTime.now().plusHours(2), assignment);
        var beforeOpening = upload("PUT", submitPath(assignment), "work.txt", "text/plain", "work".getBytes(), student);
        assertThat(beforeOpening.statusCode()).isEqualTo(409);
        assertThat(json.readTree(beforeOpening.body()).get("code").asText()).isEqualTo("SUBMISSION_NOT_ALLOWED");
        db.update("UPDATE assignment SET opening_time=NULL,deadline=? WHERE assignment_id=?", OffsetDateTime.now().minusHours(1), assignment);
        assertThat(upload("PUT", submitPath(assignment), "work.txt", "text/plain", "work".getBytes(), student).statusCode()).isEqualTo(409);
        var before = checked("GET", configPath(activity), null, owner, 200);
        var changed = checked("PATCH", configPath(activity), "{\"allow_late\":true}", owner, 200);
        assertThat(changed.get("deadline")).isEqualTo(before.get("deadline"));
        var result = submit(assignment);
        assertThat(result.get("is_late").asBoolean()).isTrue();
    }
    @Test void missingJwtAndSuspendedLocalAccessDoNotGrantAssignmentAccess() throws Exception {
        UUID activity = create();
        checked("GET", configPath(activity), null, null, 401);
        db.update("UPDATE lms_user_access SET access_status='SUSPENDED' WHERE user_id=?", student);
        checked("GET", configPath(activity), null, student, 403);
    }

    private void assertVersion(String id, JsonNode response) {
        assertThat(db.queryForObject("SELECT version FROM submission WHERE submission_id=?", Long.class, UUID.fromString(id))).isEqualTo(response.get("version").asLong());
    }
    private UUID create() throws Exception { return UUID.fromString(checked("POST", activityPath(), creation(defaultConfiguration()), owner, 201).get("activity_id").asText()); }
    private UUID assignment(UUID activity) { return db.queryForObject("SELECT assignment_id FROM assignment WHERE activity_id=?", UUID.class, activity); }
    private JsonNode submit(UUID assignment) throws Exception { var response = upload("PUT", submitPath(assignment), "work.txt", "text/plain", "work".getBytes(), student); assertThat(response.statusCode()).isEqualTo(201); return json.readTree(response.body()); }
    private String future() { return OffsetDateTime.now().plusDays(1).toString(); }
    private String defaultConfiguration() { return "{\"deadline\":\"" + future() + "\",\"allow_late\":false,\"allowed_file_types\":[\"pdf\",\"txt\",\"docx\"],\"max_file_size_mb\":5}"; }
    private String creation(String configuration) { return "{\"title\":\"Assignment\",\"activity_type\":\"ASSIGNMENT\",\"assignment\":" + configuration + "}"; }
    private String activityPath() { return "/lessons/" + lesson + "/activities"; }
    private String configPath(UUID activity) { return "/activities/" + activity + "/assignment"; }
    private String submitPath(UUID assignment) { return "/assignments/" + assignment + "/submission"; }
    private String grade(int score, long version) { return "{\"score\":" + score + ",\"feedback\":\"Reviewed\",\"version\":" + version + "}"; }
    private String token(UUID user) throws Exception { var claims = JWT.claims(); claims.put("sub", user.toString()); return JWT.token(claims); }
    private JsonNode checked(String method, String path, String body, UUID user, int expected) throws Exception {
        var response = request(method, path, body, user); assertThat(response.statusCode()).as(method + " " + path + " " + response.body()).isEqualTo(expected); return json.readTree(response.body());
    }
    private HttpResponse<String> request(String method, String path, String body, UUID user) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).header("Content-Type", "application/json");
        if (user != null) builder.header("Authorization", "Bearer " + token(user));
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> upload(String method, String path, String filename, String mime, byte[] bytes, UUID user) throws Exception {
        String boundary = "boundary-" + UUID.randomUUID(); var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes());
        body.write(bytes); body.write(("\r\n--" + boundary + "--\r\n").getBytes());
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).header("Authorization", "Bearer " + token(user))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).method(method, HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }
}
