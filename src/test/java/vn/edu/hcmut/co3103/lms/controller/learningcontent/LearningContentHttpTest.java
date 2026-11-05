package vn.edu.hcmut.co3103.lms.controller.learningcontent;

import com.nimbusds.jose.JOSEException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.entity.Material;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.MaterialRepository;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Learning content contract over a real servlet container and a real PostgreSQL schema: the
 * storage binding, the transaction boundaries and the canonical error envelope are only observable
 * end to end here, not through a mocked service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(LearningContentHttpTest.FixedClockConfiguration.class)
class LearningContentHttpTest {

    private static final String BOUNDARY = "lms-content-http-boundary";
    private static final byte[] PDF_BYTES = "%PDF-1.7 learning content probe".getBytes(StandardCharsets.UTF_8);
    private static final Path STORAGE_DIRECTORY = createStorageDirectory();
    private static final JwtTestSupport TOKENS = startTokens();

    private static JwtTestSupport startTokens() {
        try {
            return new JwtTestSupport();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Path createStorageDirectory() {
        try {
            return Files.createTempDirectory("lms-content-http-");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> JwtTestSupport.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TOKENS::jwksUri);
        registry.add("lms.storage.local.directory", STORAGE_DIRECTORY::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ClassRepository classRepository;
    @Autowired
    private ClassMemberRepository classMemberRepository;
    @Autowired
    private ChapterRepository chapterRepository;
    @Autowired
    private LessonRepository lessonRepository;
    @Autowired
    private MaterialRepository materialRepository;
    @Autowired
    private ActivityRepository activityRepository;
    @Autowired
    private LmsUserAccessRepository userRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactions;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final List<UUID> classIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    private LmsUserAccess tutor;
    private LmsUserAccess student;
    private String tutorToken;
    private String studentToken;
    private UUID classId;
    private List<UUID> raceChapterIds = List.of();

    @BeforeEach
    void createCourse() throws Exception {
        tutor = saveUser(LmsRole.TUTOR);
        student = saveUser(LmsRole.STUDENT);
        tutorToken = bearer(tutor);
        studentToken = bearer(student);
        classId = saveClass(tutor.getUserId());
        enroll(student.getUserId());
    }

    @AfterEach
    void removeFixtures() throws IOException {
        // Remove the whole content tree of this course, including rows the tests created
        // deliberately outside the tracked helpers.
        for (Chapter chapter : chapters()) {
            for (Lesson lesson : lessons(chapter.getChapterId())) {
                materials(lesson.getLessonId()).forEach(m -> materialRepository.deleteById(m.getMaterialId()));
                activities(lesson.getLessonId()).forEach(a -> activityRepository.deleteById(a.getActivityId()));
                lessonRepository.deleteById(lesson.getLessonId());
            }
            chapterRepository.deleteById(chapter.getChapterId());
        }
        classMemberRepository.deleteAll(classMemberRepository.findByClassIdAndStudentId(classId, student.getUserId())
                .map(List::of).orElse(List.of()));
        classIds.forEach(classRepository::deleteById);
        userIds.forEach(userRepository::deleteById);
        classIds.clear();
        userIds.clear();
        try (Stream<Path> files = Files.list(STORAGE_DIRECTORY)) {
            for (Path file : files.toList()) Files.deleteIfExists(file);
        }
    }

    @Test
    void tutorPlacesAnActivityAndItsVisibilityFollowsTheWholeChain() throws Exception {
        UUID lessonId = createLesson(createChapter("{\"title\":\"Basics\"}"));

        HttpResponse<String> created = post("/api/v1/lessons/" + lessonId + "/activities", tutorToken,
                "{\"title\":\"Week 1 quiz\",\"activity_type\":\"QUIZ\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode activity = json(created);
        UUID activityId = UUID.fromString(activity.get("activity_id").asString());
        assertThat(activity.get("activity_type").asString()).isEqualTo("QUIZ");
        assertThat(activity.get("sequence_order").asInt()).isZero();
        assertThat(activity.get("lesson_id").asString()).isEqualTo(lessonId.toString());
        assertThat(activity.get("status").asString()).isEqualTo("PUBLISHED");

        assertThat(get("/api/v1/lessons/" + lessonId + "/activities", studentToken).statusCode()).isEqualTo(200);
        assertThat(json(get("/api/v1/lessons/" + lessonId + "/activities", studentToken)).get(0)
                .get("activity_id").asString()).isEqualTo(activityId.toString());
        assertThat(json(get("/api/v1/classes/" + classId + "/outline", studentToken))
                .get("chapters").get(0).get("lessons").get(0).get("activities").get(0)
                .get("activity_id").asString()).isEqualTo(activityId.toString());

        patch("/api/v1/activities/" + activityId, tutorToken, "{\"status\":\"HIDDEN\"}");
        assertThat(get("/api/v1/activities/" + activityId, studentToken).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/activities/" + activityId, tutorToken).statusCode()).isEqualTo(200);

        // Only placement is patchable; the assessment type belongs to the Assessment module.
        assertThat(patch("/api/v1/activities/" + activityId, tutorToken,
                "{\"activity_type\":\"ASSIGNMENT\"}").statusCode()).isEqualTo(400);

        assertThat(delete("/api/v1/activities/" + activityId, tutorToken).statusCode()).isEqualTo(204);
        assertThat(get("/api/v1/activities/" + activityId, tutorToken).statusCode()).isEqualTo(404);
    }

    @Test
    void activityPlacementRefusesToCarryAssessmentConfiguration() throws Exception {
        UUID lessonId = createLesson(createChapter("{\"title\":\"Basics\"}"));

        HttpResponse<String> mismatch = post("/api/v1/lessons/" + lessonId + "/activities", tutorToken,
                "{\"title\":\"Week 1 quiz\",\"activity_type\":\"QUIZ\",\"quiz\":{\"time_limit_minutes\":60}}");
        HttpResponse<String> unknown = post("/api/v1/lessons/" + lessonId + "/activities", tutorToken,
                "{\"title\":\"Week 1 quiz\",\"activity_type\":\"QUIZ\",\"time_limit_minutes\":60}");

        assertThat(mismatch.statusCode()).isEqualTo(409);
        assertThat(json(mismatch).get("code").asString()).isEqualTo("ASSESSMENT_CONFIGURATION_MISMATCH");
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(json(unknown).get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(activities(lessonId)).isEmpty();
    }

    @Test
    void fractionalAndTextualPositionsAreRejectedWithoutStoringAnything() throws Exception {
        assertThat(post("/api/v1/classes/" + classId + "/chapters", tutorToken,
                "{\"title\":\"Basics\",\"sequence_order\":1.5}").statusCode()).isEqualTo(400);
        assertThat(post("/api/v1/classes/" + classId + "/chapters", tutorToken,
                "{\"title\":\"Basics\",\"sequence_order\":\"1\"}").statusCode()).isEqualTo(400);
        assertThat(chapters()).isEmpty();

        UUID lessonId = createLesson(createChapter("{\"title\":\"Basics\"}"));
        assertThat(post("/api/v1/lessons/" + lessonId + "/materials", tutorToken,
                "{\"title\":\"Notes\",\"material_type\":\"TEXT\",\"content\":\"Read\",\"sequence_order\":2.000000001}")
                .statusCode()).isEqualTo(400);
        assertThat(post("/api/v1/chapters/" + lessonChapter(lessonId) + "/lessons", tutorToken,
                "{\"title\":\"Variables\",\"estimated_duration\":1.5}").statusCode()).isEqualTo(400);
        assertThat(materials(lessonId)).isEmpty();
        assertThat(lessons(lessonChapter(lessonId))).hasSize(1);

        assertThat(post("/api/v1/lessons/" + lessonId + "/activities", tutorToken,
                "{\"title\":\"Quiz\",\"activity_type\":\"QUIZ\",\"sequence_order\":1.5}").statusCode()).isEqualTo(400);
        assertThat(activities(lessonId)).isEmpty();
    }

    @Test
    void omittedPositionsAppendAfterTheHighestStoredSlotAndExplicitOnesAreHonoured() throws Exception {
        assertThat(json(post("/api/v1/classes/" + classId + "/chapters", tutorToken,
                "{\"title\":\"First\"}")).get("sequence_order").asInt()).isZero();
        assertThat(json(post("/api/v1/classes/" + classId + "/chapters", tutorToken,
                "{\"title\":\"Second\"}")).get("sequence_order").asInt()).isEqualTo(1);
        assertThat(json(post("/api/v1/classes/" + classId + "/chapters", tutorToken,
                "{\"title\":\"Explicit\",\"sequence_order\":9}")).get("sequence_order").asInt()).isEqualTo(9);
        assertThat(json(post("/api/v1/classes/" + classId + "/chapters", tutorToken,
                "{\"title\":\"Third\"}")).get("sequence_order").asInt()).isEqualTo(10);

        assertThat(json(post("/api/v1/classes/" + classId + "/chapters", tutorToken,
                "{\"title\":\"Negative\",\"sequence_order\":-1}")).get("code").asString())
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void uploadedDocumentLandsInTheConfiguredDirectoryAndIsRemovedWithItsRow() throws Exception {
        UUID lessonId = createLesson(createChapter("{\"title\":\"Basics\"}"));

        HttpResponse<String> uploaded = uploadMaterial(lessonId, tutorToken,
                "{\"title\":\"Slides\",\"material_type\":\"DOCUMENT\"}", "slides.pdf", PDF_BYTES);
        assertThat(uploaded.statusCode()).isEqualTo(201);
        JsonNode material = json(uploaded);
        UUID materialId = UUID.fromString(material.get("material_id").asString());
        String fileUrl = material.get("file_url").asString();
        assertThat(fileUrl).startsWith("/api/v1/materials/files/");

        String fileKey = fileUrl.substring(fileUrl.lastIndexOf('/') + 1);
        // The upload has to be written where lms.storage.local.directory points, not where the
        // working directory happens to be.
        assertThat(STORAGE_DIRECTORY.resolve(fileKey)).hasBinaryContent(PDF_BYTES);

        HttpResponse<byte[]> served = getRaw(fileUrl, studentToken);
        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(served.body()).containsExactly(PDF_BYTES);

        assertThat(delete("/api/v1/materials/" + materialId, tutorToken).statusCode()).isEqualTo(204);
        assertThat(Files.exists(STORAGE_DIRECTORY.resolve(fileKey))).isFalse();
    }

    @Test
    void aStoredAssetKeyCannotBeSuppliedThroughTheJsonApi() throws Exception {
        UUID lessonId = createLesson(createChapter("{\"title\":\"Basics\"}"));
        String storedUrl = "/api/v1/materials/files/2d8f5627-6550-47db-aae0-3b262cc8e34c.pdf";

        HttpResponse<String> response = post("/api/v1/lessons/" + lessonId + "/materials", tutorToken,
                "{\"title\":\"Notes\",\"material_type\":\"DOCUMENT\",\"file_url\":\"" + storedUrl + "\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(materials(lessonId)).isEmpty();
    }

    @Test
    void archivedClassRefusesContentWritesWithTheDocumentedConflict() throws Exception {
        UUID chapterId = createChapter("{\"title\":\"Basics\"}");
        UUID lessonId = createLesson(chapterId);
        Class archived = classRepository.findById(classId).orElseThrow();
        archived.setStatus(Class.ClassStatus.ARCHIVED);
        classRepository.saveAndFlush(archived);

        assertThat(post("/api/v1/classes/" + classId + "/chapters", tutorToken,
                "{\"title\":\"Late\"}").statusCode()).isEqualTo(409);
        HttpResponse<String> response = post("/api/v1/lessons/" + lessonId + "/activities", tutorToken,
                "{\"title\":\"Quiz\",\"activity_type\":\"QUIZ\"}");
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("CLASS_ARCHIVED");
        assertThat(activities(lessonId)).isEmpty();
    }

    /**
     * The interleaving F05 measured: a content write reads ACTIVE, an archive transaction commits
     * ARCHIVED, and the write then commits anyway. The write here is proven to be parked on the
     * Class row lock by pg_stat_activity before the archive is allowed to commit, so the
     * interleaving is fixed by observation instead of by timing.
     */
    @ParameterizedTest
    @MethodSource("contentWritesThatRaceWithArchive")
    void aContentWriteParkedBehindAnArchiveObservesTheArchivedClassAndStoresNothing(String label,
            ContentWrite write) throws Exception {
        raceChapterIds = List.of(createChapter("{\"title\":\"First\"}"), createChapter("{\"title\":\"Second\"}"));

        HttpResponse<String> response = writeWhileArchiveCommits(write);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("CLASS_ARCHIVED");
        assertThat(classRepository.findById(classId).orElseThrow().getStatus())
                .isEqualTo(Class.ClassStatus.ARCHIVED);
        assertThat(chapters()).extracting(Chapter::getTitle).containsExactly("First", "Second");
        assertThat(chapters()).extracting(Chapter::getSequenceOrder).containsExactly(0, 1);
    }

    private HttpResponse<String> writeWhileArchiveCommits(ContentWrite write) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> parked = new TransactionTemplate(transactions).execute(status -> {
                Class target = classRepository.findByIdForUpdate(classId).orElseThrow();
                target.setStatus(Class.ClassStatus.ARCHIVED);
                classRepository.saveAndFlush(target);
                Future<HttpResponse<String>> attempt = executor.submit(() -> write.apply(this));
                awaitParkedOnTheClassLock(attempt);
                return attempt;
            });
            // Committing the archive is what releases the parked write.
            return parked.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timedOut) {
            throw new AssertionError("the content write stayed blocked after the archive committed", timedOut);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Parks the caller until the content write is provably queued behind this transaction on the
     * Class row. pg_stat_activity.query cannot be used for that: PostgreSQL refreshes it lazily, so
     * a backend already waiting inside SELECT ... FOR UPDATE still reports its previous statement.
     * The lock table is exact instead — the writer holds the tuple lock on "class" and waits on a
     * transaction lock this very backend granted itself.
     */
    private void awaitParkedOnTheClassLock(Future<HttpResponse<String>> attempt) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (attempt.isDone()) {
                throw new AssertionError("the content write completed without waiting for the archive");
            }
            Integer parked = jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity a
                    JOIN pg_locks queued ON queued.pid = a.pid AND queued.locktype = 'transactionid'
                         AND NOT queued.granted
                    WHERE a.datname = current_database() AND a.pid <> pg_backend_pid()
                      AND pg_blocking_pids(a.pid) @> ARRAY[pg_backend_pid()]
                      AND EXISTS (SELECT 1 FROM pg_locks taken WHERE taken.pid = a.pid
                          AND taken.locktype = 'tuple' AND taken.relation = 'class'::regclass
                          AND taken.granted)
                    """, Integer.class);
            if (parked != null && parked > 0) return;
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        throw new AssertionError("the content write never queued behind the archive on the Class row");
    }

    private interface ContentWrite {
        HttpResponse<String> apply(LearningContentHttpTest test) throws Exception;
    }

    private static Stream<Arguments> contentWritesThatRaceWithArchive() {
        return Stream.of(
                Arguments.of("create", (ContentWrite) test -> test.post(
                        "/api/v1/classes/" + test.classId + "/chapters", test.tutorToken, "{\"title\":\"Late\"}")),
                Arguments.of("update", (ContentWrite) test -> test.patch(
                        "/api/v1/chapters/" + test.raceChapterIds.getFirst(), test.tutorToken,
                        "{\"title\":\"Renamed\"}")),
                Arguments.of("reorder", (ContentWrite) test -> test.put(
                        "/api/v1/classes/" + test.classId + "/chapter-order", test.tutorToken,
                        test.objectMapper.writeValueAsString(
                                Map.of("chapter_ids", test.raceChapterIds.reversed())))));
    }

    @Test
    void forbiddenAndUnknownContentShareTheDocumentedErrorEnvelope() throws Exception {
        UUID chapterId = createChapter("{\"title\":\"Basics\"}");
        LmsUserAccess otherTutor = saveUser(LmsRole.TUTOR);

        HttpResponse<String> foreignWrite = patch("/api/v1/chapters/" + chapterId, bearer(otherTutor),
                "{\"title\":\"Hijacked\"}");
        HttpResponse<String> outsiderRead = get("/api/v1/chapters/" + chapterId, bearer(saveUser(LmsRole.STUDENT)));
        HttpResponse<String> unknown = get("/api/v1/chapters/" + UUID.randomUUID(), tutorToken);

        assertThat(foreignWrite.statusCode()).isEqualTo(403);
        assertThat(outsiderRead.statusCode()).isEqualTo(403);
        assertThat(unknown.statusCode()).isEqualTo(404);
        for (HttpResponse<String> response : List.of(foreignWrite, outsiderRead, unknown)) {
            JsonNode error = json(response);
            assertThat(error.get("code").asString()).isNotBlank();
            assertThat(error.get("message").asString()).isNotBlank();
            assertThat(response.body())
                    .doesNotContain("org.springframework", "Hibernate", "SELECT", "at ", "\\src\\main\\");
        }
    }

    private List<Chapter> chapters() {
        return chapterRepository.findByClassIdOrderBySequenceOrderAsc(classId);
    }

    private List<Lesson> lessons(UUID chapterId) {
        return lessonRepository.findByChapterIdOrderBySequenceOrderAsc(chapterId);
    }

    private List<Material> materials(UUID lessonId) {
        return materialRepository.findByLessonIdOrderBySequenceOrderAsc(lessonId);
    }

    private List<Activity> activities(UUID lessonId) {
        return activityRepository.findByLessonIdOrderBySequenceOrderAsc(lessonId);
    }

    private UUID lessonChapter(UUID lessonId) {
        return lessonRepository.findById(lessonId).orElseThrow().getChapterId();
    }

    private UUID createChapter(String body) throws Exception {
        HttpResponse<String> response = post("/api/v1/classes/" + classId + "/chapters", tutorToken, body);
        assertThat(response.statusCode()).isEqualTo(201);
        UUID chapterId = UUID.fromString(json(response).get("chapter_id").asString());
        return chapterId;
    }

    private UUID createLesson(UUID chapterId) throws Exception {
        HttpResponse<String> response = post("/api/v1/chapters/" + chapterId + "/lessons", tutorToken,
                "{\"title\":\"Variables\",\"estimated_duration\":20}");
        assertThat(response.statusCode()).isEqualTo(201);
        UUID lessonId = UUID.fromString(json(response).get("lesson_id").asString());
        return lessonId;
    }

    private HttpResponse<String> jsonRequest(String method, String path, String token, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .header("Authorization", "Bearer " + token);
        if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
        else builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return jsonRequest("POST", path, token, body);
    }

    private HttpResponse<String> patch(String path, String token, String body) throws Exception {
        return jsonRequest("PATCH", path, token, body);
    }

    private HttpResponse<String> put(String path, String token, String body) throws Exception {
        return jsonRequest("PUT", path, token, body);
    }

    private HttpResponse<String> delete(String path, String token) throws Exception {
        return jsonRequest("DELETE", path, token, null);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return jsonRequest("GET", path, token, null);
    }

    private HttpResponse<byte[]> getRaw(String path, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> uploadMaterial(UUID lessonId, String token, String metadata, String fileName,
                                                byte[] content) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"metadata\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + metadata + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + fileName + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

        return client.send(HttpRequest.newBuilder(uri("/api/v1/lessons/" + lessonId + "/materials"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return objectMapper.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String bearer(LmsUserAccess user) throws JOSEException {
        Map<String, Object> claims = TOKENS.claims();
        claims.put("sub", user.getUserId().toString());
        return TOKENS.token(claims);
    }

    private LmsUserAccess saveUser(LmsRole role) {
        UUID userId = UUID.randomUUID();
        userIds.add(userId);
        return userRepository.saveAndFlush(new LmsUserAccess(userId, "Content Test User",
                userId + "@example.com", role, AccessStatus.ACTIVE, null, null, null));
    }

    private UUID saveClass(UUID tutorId) {
        Class saved = classRepository.saveAndFlush(new Class(tutorId,
                "CONTENT-" + UUID.randomUUID().toString().substring(0, 8), "Programming Basics", null, null,
                20, Class.EnrollmentPolicy.OPEN));
        classIds.add(saved.getClassId());
        return saved.getClassId();
    }

    private void enroll(UUID studentId) {
        classMemberRepository.saveAndFlush(new ClassMember(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE, java.time.Instant.now(), java.time.Instant.now()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedJwtClock() {
            return JwtTestSupport.CLOCK;
        }
    }
}
