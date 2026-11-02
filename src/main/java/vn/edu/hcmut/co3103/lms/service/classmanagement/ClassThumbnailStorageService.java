package vn.edu.hcmut.co3103.lms.service.classmanagement;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class ClassThumbnailStorageService {
    public static final long MAX_BYTES = 5L * 1024 * 1024;

    private final Path root;
    private final String publicBaseUrl;

    public ClassThumbnailStorageService(
            @Value("${lms.storage.class-thumbnails.directory:./var/class-thumbnails}") String directory,
            @Value("${lms.storage.class-thumbnails.public-base-url:http://localhost:8081}") String publicBaseUrl
    ) {
        this(Path.of(directory), publicBaseUrl);
    }

    ClassThumbnailStorageService(Path root, String publicBaseUrl) {
        this.root = root.toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public StoredThumbnail store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalidFile();
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE");
        }

        String declaredType = file.getContentType();
        if (!isSupportedMimeType(declaredType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
        }

        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0) {
                throw invalidFile();
            }
            if (bytes.length > MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE");
            }
            DetectedImage image = detect(bytes);
            if (declaredType == null || !image.mimeType().equalsIgnoreCase(declaredType)) {
                throw invalidFile();
            }

            Files.createDirectories(root);
            Path temporary = Files.createTempFile(root, ".thumbnail-", ".part");
            try {
                Files.write(temporary, bytes);
                String fileName = UUID.randomUUID() + image.extension();
                Path destination = resolve(fileName);
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, destination);
                }
                String url = publicBaseUrl + "/class-thumbnails/" + fileName;
                return new StoredThumbnail(fileName, image.mimeType(), url);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORAGE_FAILED");
        }
    }

    public Path resolve(String fileName) {
        if (fileName == null || !fileName.matches("[0-9a-fA-F-]{36}\\.(jpg|png|webp)")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND");
        }
        Path path = root.resolve(fileName).normalize();
        if (!path.startsWith(root)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND");
        }
        return path;
    }

    public void delete(String fileName) {
        try {
            Files.deleteIfExists(resolve(fileName));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORAGE_FAILED");
        }
    }

    private DetectedImage detect(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) {
            return new DetectedImage("image/jpeg", ".jpg");
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        if (startsWith(bytes, png)) {
            return new DetectedImage("image/png", ".png");
        }
        if (bytes.length >= 12 && ascii(bytes, 0, "RIFF") && ascii(bytes, 8, "WEBP")) {
            return new DetectedImage("image/webp", ".webp");
        }
        throw invalidFile();
    }

    private boolean isSupportedMimeType(String mimeType) {
        return "image/jpeg".equalsIgnoreCase(mimeType)
                || "image/png".equalsIgnoreCase(mimeType)
                || "image/webp".equalsIgnoreCase(mimeType);
    }

    private boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean ascii(byte[] bytes, int offset, String value) {
        if (bytes.length < offset + value.length()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.toUpperCase((char) bytes[offset + index]) != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private ResponseStatusException invalidFile() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FILE");
    }

    private record DetectedImage(String mimeType, String extension) { }

    public record StoredThumbnail(String fileName, String mimeType, String url) { }
}
