package vn.edu.hcmut.co3103.lms.controller.classmanagement;

import com.nimbusds.jose.JOSEException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.hcmut.co3103.lms.support.ThumbnailImages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;
import vn.edu.hcmut.co3103.lms.service.classmanagement.ClassThumbnailStorageService;

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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the thumbnail contract through a real servlet container, which MockMvc bypasses: the
 * container's own multipart limits and the public static-resource chain are only reachable here.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ClassThumbnailUploadHttpTest.FixedClockConfiguration.class)
class ClassThumbnailUploadHttpTest {

    private static final String PUBLIC_BASE_URL = "http://static.thumbnails.test";
    private static final String PUBLIC_PATH = "/class-thumbnails/";
    private static final String BOUNDARY = "lms-thumbnail-http-boundary";
    private static final Path STORAGE_DIRECTORY = createStorageDirectory();
    private static final JwtTestSupport TOKENS = startTokens();
    private static final byte[] PNG = ThumbnailImages.png();

    private static JwtTestSupport startTokens() {
        try {
            return new JwtTestSupport();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static Path createStorageDirectory() {
        try {
            return Files.createTempDirectory("lms-thumbnail-http-");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> JwtTestSupport.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TOKENS::jwksUri);
        registry.add("lms.storage.class-thumbnails.directory", STORAGE_DIRECTORY::toString);
        registry.add("lms.storage.class-thumbnails.public-base-url", () -> PUBLIC_BASE_URL);
    }

    @AfterAll
    static void releaseResources() throws IOException {
        TOKENS.close();
        deleteStoredFiles();
        Files.deleteIfExists(STORAGE_DIRECTORY);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ClassRepository classRepository;

    @Autowired
    private LmsUserAccessRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final List<UUID> classIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    static Stream<ThumbnailImages.InvalidImage> invalidImages() {
        return ThumbnailImages.invalidImages().stream();
    }

    static Stream<ThumbnailImages.DimensionImage> allowedDimensions() {
        return ThumbnailImages.dimensionImages().stream().filter(ThumbnailImages.DimensionImage::accepted);
    }

    static Stream<ThumbnailImages.DimensionImage> excessiveDimensions() {
        return ThumbnailImages.dimensionImages().stream().filter(image -> !image.accepted());
    }

    @ParameterizedTest
    @MethodSource("allowedDimensions")
    void acceptsFourKAndExactPixelBoundaryImages(ThumbnailImages.DimensionImage image) throws Exception {
        LmsUserAccess tutor = saveUser(LmsRole.TUTOR);
        UUID classId = saveClass(tutor.getUserId());
        byte[] bytes = image.bytes();
        HttpResponse<String> response = upload(classId, bearer(tutor), image.fileName(), bytes, image.mimeType());
        assertThat(response.statusCode()).isEqualTo(200);
        String url = objectMapper.readTree(response.body()).get("thumbnail_url").asString();
        assertThat(jdbc.queryForObject("SELECT thumbnail_url FROM class WHERE class_id = ?", String.class, classId))
                .isEqualTo(url);
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        HttpResponse<byte[]> served = get(PUBLIC_PATH + fileName);
        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(served.body()).containsExactly(bytes);
    }

    @ParameterizedTest
    @MethodSource("excessiveDimensions")
    void rejectsDimensionAndPixelExcessWithoutChangingDatabaseOrFiles(ThumbnailImages.DimensionImage image)
            throws Exception {
        LmsUserAccess tutor = saveUser(LmsRole.TUTOR);
        UUID classId = saveClass(tutor.getUserId());
        String token = bearer(tutor);
        assertThat(upload(classId, token, "original.png", PNG).statusCode()).isEqualTo(200);
        var before = jdbc.queryForMap("SELECT * FROM class WHERE class_id = ?", classId);
        List<Path> filesBefore;
        try (var files = Files.list(STORAGE_DIRECTORY)) { filesBefore = files.sorted().toList(); }

        var rejected = upload(classId, token, image.fileName(), image.bytes(), image.mimeType());

        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(rejected.body()).get("code").asString()).isEqualTo("INVALID_FILE");
        assertThat(jdbc.queryForMap("SELECT * FROM class WHERE class_id = ?", classId)).isEqualTo(before);
        try (var files = Files.list(STORAGE_DIRECTORY)) {
            assertThat(files.sorted().toList()).isEqualTo(filesBefore);
        }
        assertThat(filesBefore).hasSize(1);
        assertThat(filesBefore.getFirst()).hasBinaryContent(PNG);
    }

    @ParameterizedTest
    @MethodSource("invalidImages")
    void malformedImagesDoNotReplacePersistedThumbnailOrChangeTimestamp(ThumbnailImages.InvalidImage image)
            throws Exception {
        LmsUserAccess tutor = saveUser(LmsRole.TUTOR);
        UUID classId = saveClass(tutor.getUserId());
        String token = bearer(tutor);
        HttpResponse<String> initial = upload(classId, token, "cover.png", PNG);
        assertThat(initial.statusCode()).isEqualTo(200);
        var before = jdbc.queryForMap("SELECT thumbnail_url, updated_at FROM class WHERE class_id = ?", classId);
        List<Path> filesBefore;
        try (var files = Files.list(STORAGE_DIRECTORY)) { filesBefore = files.sorted().toList(); }

        HttpResponse<String> rejected = upload(classId, token, "cover", image.bytes(), image.mimeType());

        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(rejected.body()).get("code").asString()).isEqualTo("INVALID_FILE");
        assertThat(jdbc.queryForMap("SELECT thumbnail_url, updated_at FROM class WHERE class_id = ?", classId))
                .isEqualTo(before);
        try (var files = Files.list(STORAGE_DIRECTORY)) {
            assertThat(files.sorted().toList()).isEqualTo(filesBefore);
        }
        assertThat(filesBefore).hasSize(1);
        assertThat(filesBefore.getFirst()).hasBinaryContent(PNG);
    }

    @BeforeEach
    void clearStorage() throws IOException {
        deleteStoredFiles();
    }

    @AfterEach
    void removeFixtures() {
        classIds.forEach(classRepository::deleteById);
        userIds.forEach(userRepository::deleteById);
        classIds.clear();
        userIds.clear();
    }

    @Test
    void uploadsThroughTheContainerAndServesTheFilePublicly() throws Exception {
        LmsUserAccess tutor = saveUser(LmsRole.TUTOR);
        UUID classId = saveClass(tutor.getUserId());

        HttpResponse<String> response = upload(classId, bearer(tutor), "cover.png", PNG);

        assertThat(response.statusCode()).isEqualTo(200);
        String url = objectMapper.readTree(response.body()).get("thumbnail_url").asString();
        assertThat(url).startsWith(PUBLIC_BASE_URL + PUBLIC_PATH);

        String fileName = url.substring(url.lastIndexOf('/') + 1);
        assertThat(STORAGE_DIRECTORY.resolve(fileName)).hasBinaryContent(PNG);

        // The stored URL carries the deployment's public base; the same path must resolve on this server
        // without credentials, because thumbnails are public class-catalog assets.
        HttpResponse<byte[]> served = get(PUBLIC_PATH + fileName);
        assertThat(served.statusCode()).isEqualTo(200);
        assertThat(served.body()).containsExactly(PNG);
    }

    @Test
    void theContainerRejectsABodyAboveTheDocumentedFiveMegabyteLimit() throws Exception {
        LmsUserAccess tutor = saveUser(LmsRole.TUTOR);
        UUID classId = saveClass(tutor.getUserId());
        byte[] oversized = new byte[(int) ClassThumbnailStorageService.MAX_BYTES + 1024];
        System.arraycopy(PNG, 0, oversized, 0, PNG.length);

        HttpResponse<String> response = upload(classId, bearer(tutor), "large.png", oversized);

        assertThat(response.statusCode()).isEqualTo(413);
        var error = objectMapper.readTree(response.body());
        assertThat(error.get("code").asString()).isEqualTo("FILE_TOO_LARGE");
        // This exact message comes from the MaxUploadSizeExceededException handler, proving the
        // container - not ClassThumbnailStorageService - rejected the body.
        assertThat(error.get("message").asString()).isEqualTo("The uploaded file exceeds the maximum allowed size.");
        try (Stream<Path> files = Files.list(STORAGE_DIRECTORY)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void theContainerDoesNotServeThumbnailPathsOutsideTheStorageDirectory() throws Exception {
        LmsUserAccess tutor = saveUser(LmsRole.TUTOR);
        saveClass(tutor.getUserId());

        assertThat(get(PUBLIC_PATH + "..%2f..%2fapplication.yaml").statusCode()).isNotEqualTo(200);
        assertThat(get(PUBLIC_PATH + "not-a-thumbnail.png").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> upload(UUID classId, String accessToken, String fileName, byte[] content)
            throws Exception {
        return upload(classId, accessToken, fileName, content, MediaType.IMAGE_PNG_VALUE);
    }

    private HttpResponse<String> upload(UUID classId, String accessToken, String fileName, byte[] content,
                                        String mimeType) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + fileName + "\"\r\nContent-Type: " + mimeType + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/classes/" + classId + "/thumbnail"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
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
        return userRepository.saveAndFlush(new LmsUserAccess(userId, "HTTP Test User",
                userId + "@example.com", role, AccessStatus.ACTIVE, null, null, null));
    }

    private UUID saveClass(UUID tutorId) {
        Class saved = classRepository.saveAndFlush(new Class(tutorId,
                "HTTP-" + UUID.randomUUID().toString().substring(0, 8), "Programming Basics", null, null,
                20, Class.EnrollmentPolicy.OPEN));
        classIds.add(saved.getClassId());
        return saved.getClassId();
    }

    private static void deleteStoredFiles() throws IOException {
        if (!Files.isDirectory(STORAGE_DIRECTORY)) {
            return;
        }
        try (Stream<Path> files = Files.list(STORAGE_DIRECTORY)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
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
