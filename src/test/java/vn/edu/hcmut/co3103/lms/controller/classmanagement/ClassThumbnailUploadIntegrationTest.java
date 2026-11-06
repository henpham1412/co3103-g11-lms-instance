package vn.edu.hcmut.co3103.lms.controller.classmanagement;

import vn.edu.hcmut.co3103.lms.support.ThumbnailImages;
import com.nimbusds.jose.JOSEException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;
import vn.edu.hcmut.co3103.lms.service.classmanagement.ClassThumbnailStorageService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(ClassThumbnailUploadIntegrationTest.FixedClockConfiguration.class)
class ClassThumbnailUploadIntegrationTest {

    private static final String UPLOAD_PATH = "/api/v1/classes/{id}/thumbnail";
    private static final String PUBLIC_PATH = "/class-thumbnails/";
    private static final String PUBLIC_BASE_URL = "http://thumbnails.test";
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
            return Files.createTempDirectory("lms-class-thumbnails-");
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
        try (Stream<Path> files = Files.list(STORAGE_DIRECTORY)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(STORAGE_DIRECTORY);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ClassRepository classRepository;

    @Autowired
    private LmsUserAccessRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    static Stream<Arguments> supportedImages() {
        return Stream.of(
                Arguments.of(MediaType.IMAGE_PNG_VALUE, ".png", png()),
                Arguments.of("image/jpeg", ".jpg", jpeg()),
                Arguments.of("image/webp", ".webp", webp()));
    }

    @ParameterizedTest
    @MethodSource("supportedImages")
    void storesAcceptedFormatsPersistsTheUrlAndServesTheFile(String mimeType, String extension, byte[] image)
            throws Exception {
        LmsUserAccess tutor = saveUser(LmsRole.TUTOR);
        UUID classId = saveClass(tutor.getUserId());

        String url = upload(classId, tutor, file(mimeType, image));

        assertThat(url).startsWith(PUBLIC_BASE_URL + PUBLIC_PATH).endsWith(extension);
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        assertThat(STORAGE_DIRECTORY.resolve(fileName)).hasBinaryContent(image);

        mockMvc.perform(getClass(classId, tutor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thumbnail_url").value(url));

        mockMvc.perform(get(PUBLIC_PATH + fileName))
                .andExpect(status().isOk())
                .andExpect(content().bytes(image));
    }

    @Test
    void aSecondUploadByTheOwnerReplacesThePersistedThumbnail() throws Exception {
        LmsUserAccess tutor = saveUser(LmsRole.TUTOR);
        UUID classId = saveClass(tutor.getUserId());

        String first = upload(classId, tutor, file(MediaType.IMAGE_PNG_VALUE, png()));
        String second = upload(classId, tutor, file("image/jpeg", jpeg()));

        assertThat(second).isNotEqualTo(first);
        mockMvc.perform(getClass(classId, tutor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thumbnail_url").value(second));
    }

    @Test
    void anotherTutorCannotOverwriteTheOwnersThumbnail() throws Exception {
        LmsUserAccess owner = saveUser(LmsRole.TUTOR);
        LmsUserAccess intruder = saveUser(LmsRole.TUTOR);
        UUID classId = saveClass(owner.getUserId());
        String original = upload(classId, owner, file(MediaType.IMAGE_PNG_VALUE, png()));
        int storedAfterOwnerUpload = storedFileNames().size();

        mockMvc.perform(multipart(UPLOAD_PATH, classId).file(file(MediaType.IMAGE_PNG_VALUE, png()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(intruder)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(getClass(classId, owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thumbnail_url").value(original));
        assertThat(storedFileNames()).hasSize(storedAfterOwnerUpload);
    }

    @Test
    void requiresTheAuthenticatedTutorRoleBeforeReachingStorage() throws Exception {
        LmsUserAccess student = saveUser(LmsRole.STUDENT);
        UUID classId = saveClass(student.getUserId());
        int before = storedFileNames().size();

        mockMvc.perform(multipart(UPLOAD_PATH, classId).file(file(MediaType.IMAGE_PNG_VALUE, png()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(student)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(multipart(UPLOAD_PATH, classId).file(file(MediaType.IMAGE_PNG_VALUE, png())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(storedFileNames()).hasSize(before);
    }

    @Test
    void unknownClassIsReportedWithoutWritingFile() throws Exception {
        LmsUserAccess tutor = saveUser(LmsRole.TUTOR);
        int before = storedFileNames().size();

        mockMvc.perform(multipart(UPLOAD_PATH, UUID.randomUUID()).file(file(MediaType.IMAGE_PNG_VALUE, png()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(tutor)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CLASS_NOT_FOUND"));

        assertThat(storedFileNames()).hasSize(before);
    }

    @Test
    void missingFilePartUsesTheDocumentedErrorEnvelope() throws Exception {
        LmsUserAccess tutor = saveUser(LmsRole.TUTOR);
        UUID classId = saveClass(tutor.getUserId());
        int before = storedFileNames().size();

        mockMvc.perform(multipart(UPLOAD_PATH, classId).header(HttpHeaders.AUTHORIZATION, bearer(tutor)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"));

        assertThat(storedFileNames()).hasSize(before);
    }

    @Test
    void emptyFileIsRejectedWithoutWritingFile() throws Exception {
        LmsUserAccess tutor = saveUser(LmsRole.TUTOR);
        UUID classId = saveClass(tutor.getUserId());
        int before = storedFileNames().size();

        mockMvc.perform(multipart(UPLOAD_PATH, classId)
                        .file(new MockMultipartFile("file", "cover.png", MediaType.IMAGE_PNG_VALUE, new byte[0]))
                        .header(HttpHeaders.AUTHORIZATION, bearer(tutor)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"));

        assertThat(storedFileNames()).hasSize(before);
    }

    @Test
    void undeclaredFormatIsRejectedWithoutWritingFile() throws Exception {
        LmsUserAccess tutor = saveUser(LmsRole.TUTOR);
        UUID classId = saveClass(tutor.getUserId());
        int before = storedFileNames().size();

        mockMvc.perform(multipart(UPLOAD_PATH, classId)
                        .file(file("image/gif", new byte[]{'G', 'I', 'F', '8', '7', 'a'}))
                        .header(HttpHeaders.AUTHORIZATION, bearer(tutor)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        assertThat(storedFileNames()).hasSize(before);
    }

    @Test
    void declaredTypeThatDisagreesWithTheStoredBytesIsRejected() throws Exception {
        LmsUserAccess tutor = saveUser(LmsRole.TUTOR);
        UUID classId = saveClass(tutor.getUserId());
        int before = storedFileNames().size();

        mockMvc.perform(multipart(UPLOAD_PATH, classId)
                        .file(file(MediaType.IMAGE_PNG_VALUE, jpeg()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(tutor)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"));

        mockMvc.perform(multipart(UPLOAD_PATH, classId)
                        .file(file("image/png", "not-an-image".getBytes()))
                        .header(HttpHeaders.AUTHORIZATION, bearer(tutor)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"));

        assertThat(storedFileNames()).hasSize(before);
    }

    @Test
    void oversizedFileIsRejectedWithoutWritingFile() throws Exception {
        // MockMvc does not run the container's multipart limit, so this reaches ClassThumbnailStorageService.
        LmsUserAccess tutor = saveUser(LmsRole.TUTOR);
        UUID classId = saveClass(tutor.getUserId());
        int before = storedFileNames().size();
        byte[] oversized = new byte[(int) ClassThumbnailStorageService.MAX_BYTES + 1];
        System.arraycopy(png(), 0, oversized, 0, png().length);

        mockMvc.perform(multipart(UPLOAD_PATH, classId)
                        .file(file(MediaType.IMAGE_PNG_VALUE, oversized))
                        .header(HttpHeaders.AUTHORIZATION, bearer(tutor)))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));

        assertThat(storedFileNames()).hasSize(before);
    }

    private org.springframework.test.web.servlet.RequestBuilder getClass(UUID classId, LmsUserAccess tutor)
            throws JOSEException {
        return get("/api/v1/classes/{id}", classId).header(HttpHeaders.AUTHORIZATION, bearer(tutor));
    }

    private String upload(UUID classId, LmsUserAccess tutor, MockMultipartFile image) throws Exception {
        String body = mockMvc.perform(multipart(UPLOAD_PATH, classId).file(image)
                        .header(HttpHeaders.AUTHORIZATION, bearer(tutor)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("thumbnail_url").asString();
    }

    private MockMultipartFile file(String mimeType, byte[] content) {
        return new MockMultipartFile("file", "cover", mimeType, content);
    }

    private List<String> storedFileNames() throws IOException {
        try (Stream<Path> files = Files.list(STORAGE_DIRECTORY)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private LmsUserAccess saveUser(LmsRole role) {
        UUID userId = UUID.randomUUID();
        return userRepository.saveAndFlush(new LmsUserAccess(userId, "Test User", userId + "@example.com",
                role, AccessStatus.ACTIVE, null, null, null));
    }

    private UUID saveClass(UUID tutorId) {
        return classRepository.saveAndFlush(new Class(tutorId,
                "THUMB-" + UUID.randomUUID().toString().substring(0, 8), "Programming Basics", null, null,
                20, Class.EnrollmentPolicy.OPEN)).getClassId();
    }

    private String bearer(LmsUserAccess user) throws JOSEException {
        Map<String, Object> claims = TOKENS.claims();
        claims.put("sub", user.getUserId().toString());
        return "Bearer " + TOKENS.token(claims);
    }

    private static byte[] png() {
        return ThumbnailImages.png();
    }

    private static byte[] jpeg() {
        return ThumbnailImages.jpeg();
    }

    private static byte[] webp() {
        return ThumbnailImages.webp();
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
