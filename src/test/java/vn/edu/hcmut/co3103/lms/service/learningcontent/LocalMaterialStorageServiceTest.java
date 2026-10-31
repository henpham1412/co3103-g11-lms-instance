package vn.edu.hcmut.co3103.lms.service.learningcontent;

import org.junit.jupiter.api.Test;
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
                "%PDF-1.7 test".getBytes());

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
