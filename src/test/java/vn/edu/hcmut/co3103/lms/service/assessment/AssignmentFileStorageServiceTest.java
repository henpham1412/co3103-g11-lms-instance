package vn.edu.hcmut.co3103.lms.service.assessment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.service.learningcontent.MaterialContentValidator;
import vn.edu.hcmut.co3103.lms.support.MaterialFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.*;

class AssignmentFileStorageServiceTest {
    @TempDir Path directory;
    private AssignmentFileStorageService storage() { return new AssignmentFileStorageService(directory.toString(), new MaterialContentValidator("ffprobe")); }
    @ParameterizedTest
    @CsvSource({"valid.pdf,pdf,application/pdf", "valid-encrypted.pdf,pdf,application/pdf",
            "valid.docx,docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "valid.pptx,pptx,application/vnd.openxmlformats-officedocument.presentationml.presentation"})
    void validatesDocumentBytesUsingExistingParsers(String fixture, String allowed, String mime) throws Exception {
        var stored = storage().store(new MockMultipartFile("file", "answer." + allowed, "application/octet-stream", MaterialFiles.read(fixture)), allowed, 5);
        assertThat(stored.mimeType()).isEqualTo(mime);
        assertThat(Files.readAllBytes(storage().resolve(stored.storagePath()))).isEqualTo(MaterialFiles.read(fixture));
    }
    @Test void textRenamedPdfAndClaimedPdfMimeCannotBypassTheAllowedType() throws Exception {
        reject("answer.pdf", "application/pdf", "plain text".getBytes(), "pdf");
    }
    @Test void genuinePdfRenamedTxtCannotBypassExtensionAgreement() throws Exception {
        reject("answer.txt", "text/plain", MaterialFiles.read("valid.pdf"), "pdf");
    }
    @Test void textIsDetectedIndependentlyOfClientMime() throws Exception {
        var stored = storage().store(new MockMultipartFile("file", "answer.txt", "application/pdf", "actual text".getBytes()), "text/plain", 1);
        assertThat(stored.mimeType()).isEqualTo("text/plain");
    }
    @Test void unsupportedBinaryAndEmptyFilesLeaveNoOrphan() throws Exception {
        reject("answer.txt", "text/plain", new byte[]{0, 1, 2, (byte) 0xff}, "txt");
        reject("empty.pdf", "application/pdf", new byte[0], "pdf");
    }
    @Test void aHeaderOnlyPdfIsNotAValidDocument() throws Exception {
        reject("answer.pdf", "application/pdf", "%PDF-1.7\nnot a document".getBytes(), "pdf");
    }
    @Test void explicitMimeAndExtensionAliasesAreEquivalent() throws Exception {
        var stored = storage().store(new MockMultipartFile("file", "ANSWER.PDF", "text/plain", MaterialFiles.read("valid.pdf")), ".pdf,application/pdf", 5);
        assertThat(stored.mimeType()).isEqualTo("application/pdf");
    }
    @Test void imageAndZipFormatsContinueToUseActualParserValidatedBytes() throws Exception {
        for (String format : new String[]{"png", "jpeg"}) {
            var bytes = new ByteArrayOutputStream();
            assertThat(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, bytes)).isTrue();
            String extension = format.equals("jpeg") ? "jpg" : format;
            var stored = storage().store(new MockMultipartFile("file", "image." + extension, "application/pdf", bytes.toByteArray()), extension, 1);
            assertThat(stored.mimeType()).isEqualTo("image/" + format);
        }
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) { zip.putNextEntry(new ZipEntry("work.txt")); zip.write("work".getBytes()); zip.closeEntry(); }
        assertThat(storage().store(new MockMultipartFile("file", "work.zip", "text/plain", bytes.toByteArray()), "zip", 1).mimeType()).isEqualTo("application/zip");
    }
    @Test void sizeLimitsAndStoragePathValidationRemainEnforced() throws Exception {
        assertThatThrownBy(() -> storage().store(new MockMultipartFile("file", "answer.txt", "text/plain", new byte[1024 * 1024 + 1]), "txt", 1))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(413));
        assertThatThrownBy(() -> storage().resolve("../../secret.pdf")).isInstanceOf(ResponseStatusException.class);
        try (var files = Files.list(directory)) { assertThat(files.toList()).isEmpty(); }
    }
    private void reject(String name, String declared, byte[] bytes, String allowed) throws Exception {
        assertThatThrownBy(() -> storage().store(new MockMultipartFile("file", name, declared, bytes), allowed, 5))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(415));
        try (var files = Files.list(directory)) { assertThat(files.toList()).isEmpty(); }
    }
}
