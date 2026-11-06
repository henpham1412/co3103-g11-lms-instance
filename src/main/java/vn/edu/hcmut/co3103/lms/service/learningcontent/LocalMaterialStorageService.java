package vn.edu.hcmut.co3103.lms.service.learningcontent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.Material;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

@Service
public class LocalMaterialStorageService {
    public static final long DOCUMENT_MAX_BYTES = 50L * 1024 * 1024;
    public static final long VIDEO_MAX_BYTES = 200L * 1024 * 1024;
    private static final String FILE_KEY_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(pdf|docx|pptx|mp4|webm)";
    private final Path root;
    private final MaterialContentValidator validator;

    @Autowired
    public LocalMaterialStorageService(
            @Value("${lms.storage.local.directory:./var/lms-files}") String directory,
            MaterialContentValidator validator) {
        this(Path.of(directory), validator);
    }

    LocalMaterialStorageService(Path root) {
        this(root, new MaterialContentValidator("ffprobe"));
    }

    LocalMaterialStorageService(Path root, MaterialContentValidator validator) {
        this.validator = validator;
        this.root = root.toAbsolutePath().normalize();
    }

    public StoredFile store(MultipartFile file, Material.MaterialType type) {
        if (type != Material.MaterialType.DOCUMENT && type != Material.MaterialType.VIDEO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_MATERIAL_TYPE_FOR_UPLOAD");
        }
        long limit = type == Material.MaterialType.DOCUMENT ? DOCUMENT_MAX_BYTES : VIDEO_MAX_BYTES;
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_FILE");
        }
        if (file.getSize() > limit) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE");
        }

        Path temporary = null;
        try {
            Files.createDirectories(root);
            temporary = Files.createTempFile(root, ".upload-", ".part");
            file.transferTo(temporary);
            String mimeType = detectAndValidate(temporary, type);
            String extension = extensionFor(mimeType);
            String key = UUID.randomUUID() + extension;
            Path destination = resolve(key);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
            temporary = null;
            return new StoredFile(key, mimeType, Files.size(destination));
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
        if (key == null || !key.matches(FILE_KEY_PATTERN)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND");
        }
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND");
        }
        return resolved;
    }

    public void delete(String key) {
        try { Files.deleteIfExists(resolve(key)); }
        catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORAGE_FAILED");
        }
    }

    private String detectAndValidate(Path file, Material.MaterialType type) throws IOException {
        long size = Files.size(file);
        long max = type == Material.MaterialType.DOCUMENT ? DOCUMENT_MAX_BYTES : VIDEO_MAX_BYTES;
        if (size == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EMPTY_FILE");
        if (size > max) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE");

        return type == Material.MaterialType.DOCUMENT
                ? validator.documentType(file) : validator.videoType(file);
    }

    private String extensionFor(String mimeType) {
        return switch (mimeType.toLowerCase(Locale.ROOT)) {
            case "application/pdf" -> ".pdf";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> ".pptx";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            default -> throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
        };
    }

    public record StoredFile(String key, String mimeType, long size) { }
}
