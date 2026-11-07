package vn.edu.hcmut.co3103.lms.controller.learningcontent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.edu.hcmut.co3103.lms.support.MaterialFiles;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.edu.hcmut.co3103.lms.dto.request.MaterialRequest;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Material;
import vn.edu.hcmut.co3103.lms.service.learningcontent.ChapterService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChapterControllerMaterialApiTest {
    private static final UUID TUTOR_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID STUDENT_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID LESSON_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String FILE_KEY = "2d8f5627-6550-47db-aae0-3b262cc8e34c.pdf";

    private final ChapterService chapterService = mock(ChapterService.class);
    private MockMvc mockMvc;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ChapterController(chapterService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void multipartUploadBindsMetadataAndFileAndReturnsCreatedMaterial() throws Exception {
        JwtAuthenticationToken authentication = authenticate(TUTOR_ID, "TUTOR");
        MaterialRequest request = new MaterialRequest("Lesson notes", Material.MaterialType.DOCUMENT,
                null, null, 2, Chapter.VisibilityStatus.PUBLISHED);
        Material created = new Material(LESSON_ID, "Lesson notes", Material.MaterialType.DOCUMENT,
                "/api/v1/materials/files/" + FILE_KEY, null, 2, Chapter.VisibilityStatus.PUBLISHED);
        UUID materialId = UUID.fromString("44444444-4444-4444-8444-444444444444");
        ReflectionTestUtils.setField(created, "materialId", materialId);
        when(chapterService.createUploadedMaterial(eq(LESSON_ID), eq(TUTOR_ID), any(MaterialRequest.class), any()))
                .thenReturn(created);

        MockMultipartFile metadata = new MockMultipartFile("metadata", "metadata.json",
                MediaType.APPLICATION_JSON_VALUE,
                ("{\"title\":\"Lesson notes\",\"material_type\":\"DOCUMENT\","
                        + "\"sequence_order\":2,\"status\":\"PUBLISHED\"}").getBytes());
        MockMultipartFile file = new MockMultipartFile("file", "notes.pdf", MediaType.APPLICATION_PDF_VALUE,
                MaterialFiles.read("valid.pdf"));

        mockMvc.perform(multipart("/api/v1/lessons/{lessonId}/materials", LESSON_ID)
                        .file(metadata)
                        .file(file)
                        .principal(authentication))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.material_id").value(materialId.toString()))
                .andExpect(jsonPath("$.material_type").value("DOCUMENT"))
                .andExpect(jsonPath("$.file_url").value("/api/v1/materials/files/" + FILE_KEY));

        verify(chapterService).createUploadedMaterial(eq(LESSON_ID), eq(TUTOR_ID), eq(request), any());
    }

    @Test
    void multipartUploadRequiresTheFilePart() throws Exception {
        JwtAuthenticationToken authentication = authenticate(TUTOR_ID, "TUTOR");
        MockMultipartFile metadata = new MockMultipartFile("metadata", "metadata.json",
                MediaType.APPLICATION_JSON_VALUE,
                "{\"title\":\"Lesson notes\",\"material_type\":\"DOCUMENT\",\"sequence_order\":0}"
                        .getBytes());

        mockMvc.perform(multipart("/api/v1/lessons/{lessonId}/materials", LESSON_ID)
                        .file(metadata)
                        .principal(authentication))
                .andExpect(status().isBadRequest());

        verify(chapterService, never()).createUploadedMaterial(any(), any(), any(), any());
    }

    @Test
    void studentDownloadReturnsFileWithContentTypeAndDelegatesStudentIdentity() throws Exception {
        JwtAuthenticationToken authentication = authenticate(STUDENT_ID, "STUDENT");
        Path file = temporaryDirectory.resolve(FILE_KEY);
        Files.writeString(file, "sample pdf bytes");
        when(chapterService.getMaterialFile(FILE_KEY, STUDENT_ID, false))
                .thenReturn(new ChapterService.DownloadableMaterial(file, "application/pdf", "Lesson notes.pdf"));

        mockMvc.perform(get("/api/v1/materials/files/{fileKey}", FILE_KEY).principal(authentication))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"Lesson notes.pdf\""))
                .andExpect(content().string("sample pdf bytes"));

        verify(chapterService).getMaterialFile(FILE_KEY, STUDENT_ID, false);
    }

    @Test
    void tutorDownloadDelegatesTutorRoleToOwnershipCheck() throws Exception {
        JwtAuthenticationToken authentication = authenticate(TUTOR_ID, "TUTOR");
        Path file = temporaryDirectory.resolve(FILE_KEY);
        Files.writeString(file, "sample pdf bytes");
        when(chapterService.getMaterialFile(FILE_KEY, TUTOR_ID, true))
                .thenReturn(new ChapterService.DownloadableMaterial(file, "application/pdf", "Lesson notes.pdf"));

        mockMvc.perform(get("/api/v1/materials/files/{fileKey}", FILE_KEY).principal(authentication))
                .andExpect(status().isOk());

        verify(chapterService).getMaterialFile(FILE_KEY, TUTOR_ID, true);
    }

    private JwtAuthenticationToken authenticate(UUID userId, String role) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return authentication;
    }
}
