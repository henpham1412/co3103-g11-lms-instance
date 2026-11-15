package vn.edu.hcmut.co3103.lms.service.assessment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import vn.edu.hcmut.co3103.lms.service.learningcontent.MaterialContentValidator;
import java.util.Locale;
import java.util.UUID;

@Service
public class AssignmentFileStorageService {
    private final Path root;
    private final MaterialContentValidator documents;

    public AssignmentFileStorageService(
            @Value("${lms.storage.assignments.directory:./var/assignment-files}") String directory,
            MaterialContentValidator documents) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.documents = documents;
    }

    public StoredFile store(MultipartFile file, String allowedTypes, Integer maxSizeMb) {
        if (file == null || file.isEmpty()) {
            throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
        }
        if (maxSizeMb != null && file.getSize() > maxSizeMb * 1024L * 1024L) {
            throw error(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE");
        }

        Path temporary = null;
        try {
            Files.createDirectories(root);
            temporary = Files.createTempFile(root, ".assignment-", ".part");
            file.transferTo(temporary);
            String mimeType = detect(temporary);
            String extension;
            try {
                extension = AssignmentFileTypes.extension(file.getOriginalFilename());
                if (!mimeType.equals(AssignmentFileTypes.mime(extension)) || !AssignmentFileTypes.allowed(allowedTypes).contains(mimeType))
                    throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
            } catch (IllegalArgumentException invalidType) { throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE"); }
            String safeName = safeName(file.getOriginalFilename());
            long size = Files.size(temporary);
            String key = UUID.randomUUID() + "." + extension;
            Path destination = root.resolve(key).normalize();
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            return new StoredFile(key, mimeType, size, safeName);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORAGE_FAILED");
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    public Path resolve(String key) {
        if (key == null || key.isBlank() || key.contains("/") || key.contains("\\")
                || !key.matches("[0-9a-fA-F-]+\\.[a-z0-9]+")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND");
        }
        return root.resolve(key).normalize();
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORAGE_FAILED");
        }
    }

    private String detect(Path file) throws IOException {
        // Reuse the repository's PDFBox/POI validation, not a filename/client MIME or header-only detector.
        try { return documents.documentType(file); }
        catch (ResponseStatusException unsupportedDocument) {
            if (unsupportedDocument.getStatusCode().value() != 415) throw unsupportedDocument;
        }
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(input);
            if (readers.hasNext()) {
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg")) throw unsupported();
                    if (reader.read(0) == null) throw unsupported();
                    return format.equals("png") ? "image/png" : "image/jpeg";
                } finally { reader.dispose(); }
            }
        } catch (javax.imageio.IIOException invalidImage) { throw unsupported(); }
        try (var zip = new ZipFile(file.toFile())) {
            // A malformed Office package must not be relabelled as an ordinary ZIP.
            if (zip.getEntry("[Content_Types].xml") != null || zip.getEntry("word/document.xml") != null
                    || zip.getEntry("ppt/presentation.xml") != null) throw unsupported();
            return "application/zip";
        } catch (java.util.zip.ZipException notZip) { /* Try strictly decoded text last. */ }
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new java.io.InputStreamReader(Files.newInputStream(file), decoder)) {
            char[] buffer = new char[4096];
            int length;
            while ((length = reader.read(buffer)) != -1) {
                for (int i = 0; i < length; i++) {
                    char ch = buffer[i];
                    if (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t' && ch != '\f') throw unsupported();
                }
            }
            return "text/plain";
        } catch (java.nio.charset.CharacterCodingException binary) { throw unsupported(); }
    }

    private ResponseStatusException unsupported() { return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE"); }

    private String safeName(String name) {
        if (name == null || name.isBlank()) return "submission";
        String baseName = Path.of(name).getFileName().toString();
        String sanitized = baseName.replaceAll("[\\p{Cntrl}\"\\\\]", "_").trim();
        return sanitized.isBlank() ? "submission" : sanitized;
    }

    private ResponseStatusException error(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }

    public record StoredFile(String storagePath, String mimeType, long sizeBytes, String fileName) { }
}
