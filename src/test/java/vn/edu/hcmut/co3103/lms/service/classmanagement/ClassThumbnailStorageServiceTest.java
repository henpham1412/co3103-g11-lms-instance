package vn.edu.hcmut.co3103.lms.service.classmanagement;

import vn.edu.hcmut.co3103.lms.support.ThumbnailImages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClassThumbnailStorageServiceTest {
    @TempDir
    Path directory;

    private ClassThumbnailStorageService service() {
        return new ClassThumbnailStorageService(directory, "http://localhost:8081");
    }

    static java.util.stream.Stream<ThumbnailImages.InvalidImage> invalidImages() {
        return ThumbnailImages.invalidImages().stream();
    }

    @ParameterizedTest
    @MethodSource("invalidImages")
    void rejectsIncompleteAndCorruptedImagesBeforeWriting(
            ThumbnailImages.InvalidImage image) throws Exception {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service().store(new MockMultipartFile("file", "cover", image.mimeType(), image.bytes())));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("INVALID_FILE", error.getReason());
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void storesSupportedPngAndReturnsPublicUrl() throws Exception {
        byte[] png = ThumbnailImages.png();
        var stored = service().store(new MockMultipartFile("file", "cover.png", "image/png", png));

        assertEquals("image/png", stored.mimeType());
        assertEquals("http://localhost:8081/class-thumbnails/" + stored.fileName(), stored.url());
        assertEquals(directory.resolve(stored.fileName()).toAbsolutePath().normalize(),
                service().resolve(stored.fileName()));
        assertEquals(png.length, Files.size(service().resolve(stored.fileName())));
    }

    @Test
    void storesSupportedJpegAndWebpAndLeavesNoTemporaryPartFile() throws Exception {
        var jpeg = service().store(new MockMultipartFile("file", "cover.jpg", "image/jpeg",
                ThumbnailImages.jpeg()));
        var webp = service().store(new MockMultipartFile("file", "cover.webp", "image/webp",
                ThumbnailImages.webp()));

        assertEquals(".jpg", jpeg.fileName().substring(jpeg.fileName().lastIndexOf('.')));
        assertEquals(".webp", webp.fileName().substring(webp.fileName().lastIndexOf('.')));
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void rejectsEmptyOrMissingFileWithoutWriting() throws Exception {
        ResponseStatusException empty = assertThrows(ResponseStatusException.class,
                () -> service().store(new MockMultipartFile("file", "cover.png", "image/png", new byte[0])));
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());

        ResponseStatusException missing = assertThrows(ResponseStatusException.class,
                () -> service().store(null));
        assertEquals(HttpStatus.BAD_REQUEST, missing.getStatusCode());

        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsInvalidImageSignatureAndMismatchedMimeType() {
        ResponseStatusException invalidImage = assertThrows(ResponseStatusException.class,
                () -> service().store(new MockMultipartFile("file", "cover.png", "image/png",
                        new byte[]{1, 2, 3})));
        assertEquals(HttpStatus.BAD_REQUEST, invalidImage.getStatusCode());

        ResponseStatusException mismatch = assertThrows(ResponseStatusException.class,
                () -> service().store(new MockMultipartFile("file", "cover.png", "image/jpeg",
                        new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})));
        assertEquals(HttpStatus.BAD_REQUEST, mismatch.getStatusCode());

        ResponseStatusException unsupportedType = assertThrows(ResponseStatusException.class,
                () -> service().store(new MockMultipartFile("file", "cover.gif", "image/gif",
                        new byte[]{'G', 'I', 'F'})));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, unsupportedType.getStatusCode());
    }

    @Test
    void rejectsOversizedFileAndUnsafePath() {
        ResponseStatusException oversized = assertThrows(ResponseStatusException.class,
                () -> service().store(new MockMultipartFile("file", "large.png", "image/png",
                        new byte[(int) ClassThumbnailStorageService.MAX_BYTES + 1])));
        assertEquals(HttpStatus.CONTENT_TOO_LARGE, oversized.getStatusCode());

        ResponseStatusException unsafe = assertThrows(ResponseStatusException.class,
                () -> service().resolve("../outside.png"));
        assertEquals(HttpStatus.NOT_FOUND, unsafe.getStatusCode());
    }

    @Test
    void deletesOnlyGeneratedThumbnailFiles() throws Exception {
        var service = service();
        var stored = service.store(new MockMultipartFile("file", "cover.jpg", "image/jpeg",
                ThumbnailImages.jpeg()));
        service.delete(stored.fileName());

        assertFalse(Files.exists(directory.resolve(stored.fileName())));
    }
}
