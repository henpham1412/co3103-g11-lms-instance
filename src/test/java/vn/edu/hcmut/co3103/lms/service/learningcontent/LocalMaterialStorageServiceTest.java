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
    void acceptsIsoBrandsThatActuallyCarryVideoAndStoresThemAsMp4() throws IOException {
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory);
        byte[] ftyp = mp4Header("isom", "isom", "mp42", "avc1");

        LocalMaterialStorageService.StoredFile stored = storage.store(
                videoUpload("clip.mp4", ftyp), Material.MaterialType.VIDEO);

        assertTrue(stored.key().endsWith(".mp4"));
        assertEquals("video/mp4", stored.mimeType());
    }

    @Test
    void rejectsContainersThatShareTheMp4MagicButAreNotVideo() throws IOException {
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory);

        assertUnsupportedVideo(mp4Header("qt  ", "qt  "));
        assertUnsupportedVideo(mp4Header("M4A ", "M4A ", "mp42"));
        assertUnsupportedVideo(mp4Header("3gp5", "3gp5"));
    }

    @Test
    void distinguishesWebmFromMatroskaByItsDocType() throws IOException {
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory);

        LocalMaterialStorageService.StoredFile stored = storage.store(
                videoUpload("clip.webm", ebmlHeader("webm")), Material.MaterialType.VIDEO);
        assertTrue(stored.key().endsWith(".webm"));
        assertEquals("video/webm", stored.mimeType());

        assertUnsupportedVideo(ebmlHeader("matroska"));
    }

    @Test
    void rejectsAFileKeyThatIsNotAServerMintedUuid() {
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory);

        assertThrows(ResponseStatusException.class,
                () -> storage.resolve("------------------------------------------------.pdf"));
    }

    private void assertUnsupportedVideo(byte[] body) {
        LocalMaterialStorageService storage = new LocalMaterialStorageService(temporaryDirectory);
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> storage.store(videoUpload("clip.bin", body), Material.MaterialType.VIDEO));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception.getStatusCode());
    }

    private MockMultipartFile videoUpload(String name, byte[] body) {
        return new MockMultipartFile("file", name, "video/mp4", body);
    }

    private static byte[] mp4Header(String majorBrand, String... compatibleBrands) {
        int size = 8 + 4 + 4 + compatibleBrands.length * 4;
        byte[] header = new byte[size];
        header[0] = (byte) (size >>> 24);
        header[1] = (byte) (size >>> 16);
        header[2] = (byte) (size >>> 8);
        header[3] = (byte) size;
        System.arraycopy("ftyp".getBytes(), 0, header, 4, 4);
        System.arraycopy(majorBrand.getBytes(), 0, header, 8, 4);
        System.arraycopy(new byte[4], 0, header, 12, 4);
        int offset = 16;
        for (String brand : compatibleBrands) {
            System.arraycopy(brand.getBytes(), 0, header, offset, 4);
            offset += 4;
        }
        return header;
    }

    private static byte[] ebmlHeader(String docType) {
        byte[] body = new byte[16 + docType.length()];
        body[0] = 0x1a; body[1] = 0x45; body[2] = (byte) 0xdf; body[3] = (byte) 0xa3;
        body[4] = (byte) 0x8f;
        System.arraycopy(new byte[]{0x42, (byte) 0x82, (byte) (0x80 + docType.length())}, 0, body, 8, 3);
        System.arraycopy(docType.getBytes(), 0, body, 11, docType.length());
        return body;
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
