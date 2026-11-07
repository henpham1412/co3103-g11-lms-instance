package vn.edu.hcmut.co3103.lms.service.learningcontent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import vn.edu.hcmut.co3103.lms.support.MaterialFiles;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.Material;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalMaterialStorageServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesPdfBasedOnFileContentAndIgnoresTheSubmittedExtension() throws IOException {
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory);
        MockMultipartFile upload = new MockMultipartFile("file", "payload.exe", "application/octet-stream",
                MaterialFiles.read("valid.pdf"));

        LocalMaterialStorageService.StoredFile stored = storage.store(upload, Material.MaterialType.DOCUMENT);

        assertTrue(stored.key().endsWith(".pdf"));
        assertEquals("application/pdf", stored.mimeType());
        assertTrue(Files.isRegularFile(storage.resolve(stored.key())));
        assertEquals(upload.getSize(), stored.size());
    }

    @Test
    void rejectsFileWhoseContentDoesNotMatchAnAllowedTypeAndCleansPartialUpload() throws IOException {
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory);
        MockMultipartFile fakePdf = new MockMultipartFile("file", "note.pdf", "application/pdf",
                "this is not a pdf".getBytes());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> storage.store(fakePdf, Material.MaterialType.DOCUMENT));

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception.getStatusCode());
        try (var files = Files.list(temporaryDirectory)) { assertEquals(0, files.count()); }
    }

    @Test
    void rejectsOversizedUploadBeforeReadingIt() throws IOException {
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory);
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn(LocalMaterialStorageService.DOCUMENT_MAX_BYTES + 1);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> storage.store(oversized, Material.MaterialType.DOCUMENT));

        assertEquals(HttpStatus.CONTENT_TOO_LARGE, exception.getStatusCode());
        verify(oversized, never()).transferTo(org.mockito.ArgumentMatchers.any(Path.class));
    }

    @Test
    void rejectsPathTraversalAndUnknownExtensions() {
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory);

        assertThrows(ResponseStatusException.class, () -> storage.resolve("../../secret.pdf"));
        assertThrows(ResponseStatusException.class, () -> storage.resolve("00000000-0000-0000-0000-000000000000.exe"));
    }

    @ParameterizedTest
    @CsvSource({"valid-encrypted.pdf,DOCUMENT,application/pdf", "valid.pdf,DOCUMENT,application/pdf", "valid.docx,DOCUMENT,application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "valid.pptx,DOCUMENT,application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "valid.mp4,VIDEO,video/mp4", "valid-faststart.mp4,VIDEO,video/mp4", "valid-fragmented.mp4,VIDEO,video/mp4", "valid-size-zero.mp4,VIDEO,video/mp4", "valid-extended-box.mp4,VIDEO,video/mp4", "valid-h264.mp4,VIDEO,video/mp4",
            "valid.webm,VIDEO,video/webm", "valid-vp9.webm,VIDEO,video/webm"})
    void storesRealSupportedFormatsWithoutTrustingSubmittedNameOrMime(String fixture,
            Material.MaterialType type, String mime) throws IOException {
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory);
        byte[] bytes = MaterialFiles.read(fixture);
        var stored = storage.store(new MockMultipartFile("file", "misleading.exe", "application/octet-stream", bytes), type);
        assertEquals(mime, stored.mimeType());
        assertEquals(bytes.length, stored.size());
        org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, Files.readAllBytes(storage.resolve(stored.key())));
    }

    @ParameterizedTest
    @MethodSource("invalidFiles")
    void rejectsMalformedOrUnsupportedContentWithoutLeavingAFile(MaterialFiles.InvalidFile fixture) throws IOException {
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory);
        var exception = assertThrows(ResponseStatusException.class, () -> storage.store(
                new MockMultipartFile("file", "claimed.pdf", "application/pdf", fixture.bytes()), fixture.type()));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", exception.getReason());
        try (var files = Files.list(temporaryDirectory)) { assertEquals(0, files.count()); }
    }

    static java.util.stream.Stream<MaterialFiles.InvalidFile> invalidFiles() {
        return MaterialFiles.invalidFiles().stream();
    }

    @ParameterizedTest
    @MethodSource("incompleteMp4Files")
    void incompleteMp4DoesNotLeaveANewFileOrAlterAnExistingFile(MaterialFiles.InvalidFile fixture)
            throws IOException {
        Path existing = temporaryDirectory.resolve("unrelated.txt");
        Files.writeString(existing, "Preserve existing bytes");
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory);
        var error = assertThrows(ResponseStatusException.class, () -> storage.store(
                new MockMultipartFile("file", "valid.mp4", "video/mp4", fixture.bytes()), fixture.type()));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, error.getStatusCode());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", error.getReason());
        assertEquals("Preserve existing bytes", Files.readString(existing));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(java.util.List.of(existing), files.toList());
        }
    }

    static java.util.stream.Stream<MaterialFiles.InvalidFile> incompleteMp4Files() {
        return MaterialFiles.incompleteMp4Files().stream();
    }

    @Test
    void missingVideoProbeFailsClosedAndRemovesPartialUpload() throws IOException {
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory,
                new MaterialContentValidator(temporaryDirectory.resolve("missing-ffprobe").toString()));
        var error = assertThrows(ResponseStatusException.class, () -> storage.store(
                new MockMultipartFile("file", "valid.mp4", "video/mp4", MaterialFiles.read("valid.mp4")),
                Material.MaterialType.VIDEO));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, error.getStatusCode());
        assertEquals("FILE_STORAGE_FAILED", error.getReason());
        try (var files = Files.list(temporaryDirectory)) { assertEquals(0, files.count()); }
    }

    @Test
    void rejectsAFileKeyThatIsNotAServerMintedUuid() {
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory);

        assertThrows(ResponseStatusException.class,
                () -> storage.resolve("------------------------------------------------.pdf"));
    }

    @Test
    void interruptedTransferDoesNotLeaveTemporaryFiles() throws IOException {
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory);
        MultipartFile interrupted = mock(MultipartFile.class);
        when(interrupted.isEmpty()).thenReturn(false);
        when(interrupted.getSize()).thenReturn(100L);
        org.mockito.Mockito.doAnswer(invocation -> {
            Path path = invocation.getArgument(0);
            Files.writeString(path, "partial");
            throw new IOException("interrupted");
        }).when(interrupted).transferTo(org.mockito.ArgumentMatchers.any(Path.class));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> storage.store(interrupted, Material.MaterialType.DOCUMENT));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, exception.getStatusCode());
        try (var files = Files.list(temporaryDirectory)) { assertFalse(files.findAny().isPresent()); }
    }
}
