package vn.edu.hcmut.co3103.lms.service.assessment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipFile;
import java.util.Locale;
import java.util.UUID;

@Service
public class AssignmentFileStorageService {
    private final Path root;

    public AssignmentFileStorageService(
            @Value("${lms.storage.assignments.directory:./var/assignment-files}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
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
            String mimeType = detect(temporary, file.getContentType());
            if ("application/octet-stream".equals(mimeType)) {
                throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
            }
            if (!isAllowed(mimeType, file.getOriginalFilename(), allowedTypes)) {
                throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
            }
            String key = UUID.randomUUID() + extension(mimeType, file.getOriginalFilename());
            Path destination = root.resolve(key).normalize();
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            return new StoredFile(key, mimeType, Files.size(destination), safeName(file.getOriginalFilename()));
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

    private String detect(Path file, String declared) throws IOException {
        byte[] header = new byte[8192];
        int length;
        try (InputStream input = Files.newInputStream(file)) {
            length = input.read(header);
        }
        if (length < 0) length = 0;
        if (length == 0) throw error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
        if (startsWith(header, length, "%PDF-")) return "application/pdf";
        if (startsWith(header, length, "PK")) return officeMime(file);
        if (startsWithBytes(header, length, new byte[]{(byte) 0x89, 'P', 'N', 'G'})) {
            return "image/png";
        }
        if (startsWithBytes(header, length, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return "image/jpeg";
        }
        if (isText(header, length)) return "text/plain";
        return "application/octet-stream";
    }

    private String officeMime(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.getEntry("word/document.xml") != null
                    && zip.getEntry("[Content_Types].xml") != null) {
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            }
            if (zip.getEntry("ppt/presentation.xml") != null
                    && zip.getEntry("[Content_Types].xml") != null) {
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            }
        } catch (java.util.zip.ZipException exception) {
            return "application/octet-stream";
        }
        return "application/zip";
    }

    private boolean isAllowed(String mime, String fileName, String allowedTypes) {
        if (allowedTypes == null || allowedTypes.isBlank()) return true;
        String extension = "";
        if (fileName != null && fileName.lastIndexOf('.') >= 0) {
            extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        }
        for (String token : allowedTypes.split(",")) {
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            boolean officeTypeMatch = (mime.endsWith("wordprocessingml.document")
                    && (normalized.equals("docx") || normalized.equals(".docx")))
                    || (mime.endsWith("presentationml.presentation")
                    && (normalized.equals("pptx") || normalized.equals(".pptx")));
            boolean extensionMatch = !"application/zip".equals(mime)
                    && !mime.endsWith("wordprocessingml.document")
                    && !mime.endsWith("presentationml.presentation")
                    && (normalized.equals(extension) || normalized.equals("." + extension));
            if (normalized.equals(mime.toLowerCase(Locale.ROOT))
                    || officeTypeMatch
                    || extensionMatch
                    || (normalized.endsWith("/*") && mime.startsWith(normalized.substring(0, normalized.length() - 1)))) {
                return true;
            }
        }
        return false;
    }

    private String extension(String mime, String name) {
        String lower = mime.toLowerCase(Locale.ROOT);
        if (lower.equals("application/pdf")) return ".pdf";
        if (lower.equals("text/plain")) return ".txt";
        if (lower.contains("wordprocessingml")) return ".docx";
        if (lower.contains("presentationml")) return ".pptx";
        if (name != null && name.lastIndexOf('.') >= 0) {
            String ext = name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            if (ext.matches("\\.[a-z0-9]{1,10}")) return ext;
        }
        return ".bin";
    }

    private String safeName(String name) {
        if (name == null || name.isBlank()) return "submission";
        String baseName = Path.of(name).getFileName().toString();
        String sanitized = baseName.replaceAll("[\\p{Cntrl}\"\\\\]", "_").trim();
        return sanitized.isBlank() ? "submission" : sanitized;
    }

    private boolean startsWith(byte[] bytes, int length, String value) {
        byte[] prefix = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }

    private boolean startsWithBytes(byte[] bytes, int length, byte[] prefix) {
        if (length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }

    private boolean isText(byte[] bytes, int length) {
        int controls = 0;
        for (int i = 0; i < length; i++) {
            byte value = bytes[i];
            int unsigned = value & 0xff;
            if (unsigned == 0) return false;
            if (unsigned < 9 || (unsigned > 13 && unsigned < 32)) controls++;
        }
        return controls < Math.max(1, length / 100);
    }

    private ResponseStatusException error(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }

    public record StoredFile(String storagePath, String mimeType, long sizeBytes, String fileName) { }
}
