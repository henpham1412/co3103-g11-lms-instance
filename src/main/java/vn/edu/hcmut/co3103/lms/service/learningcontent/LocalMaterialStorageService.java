package vn.edu.hcmut.co3103.lms.service.learningcontent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.Material;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipFile;

@Service
public class LocalMaterialStorageService {
    public static final long DOCUMENT_MAX_BYTES = 50L * 1024 * 1024;
    public static final long VIDEO_MAX_BYTES = 200L * 1024 * 1024;
    private static final Set<String> MP4_VIDEO_BRANDS = Set.of(
            "isom", "iso2", "iso4", "iso5", "iso6", "isoj", "mp41", "mp42", "mp71", "avc1", "M4V ", "dash", "msdh");
    private static final int EBML_HEAD_BYTES = 1024;
    private static final String FILE_KEY_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(pdf|docx|pptx|mp4|webm)";
    private final Path root;

    @Autowired
    public LocalMaterialStorageService(
            @Value("${lms.storage.local.directory:./var/lms-files}") String directory) {
        this(Path.of(directory));
    }

    LocalMaterialStorageService(Path root) {
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

        byte[] header = new byte[16];
        int read;
        try (InputStream input = Files.newInputStream(file)) { read = input.read(header); }
        if (type == Material.MaterialType.DOCUMENT) {
            if (read >= 5 && header[0] == '%' && header[1] == 'P' && header[2] == 'D'
                    && header[3] == 'F' && header[4] == '-') return "application/pdf";
            if (isZip(header, read) && isOfficeDocument(file, "word/document.xml")) {
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            }
            if (isZip(header, read) && isOfficeDocument(file, "ppt/presentation.xml")) {
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            }
        } else {
            if (read >= 12 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p'
                    && isMp4VideoBrand(header, read)) {
                return "video/mp4";
            }
            if (read >= 4 && (header[0] & 0xff) == 0x1a && (header[1] & 0xff) == 0x45
                    && (header[2] & 0xff) == 0xdf && (header[3] & 0xff) == 0xa3
                    && "webm".equals(ebmlDocType(file))) return "video/webm";
        }
        throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
    }

    /**
     * An ISO base media file always opens with an {@code ftyp} box, but the brand inside it decides
     * what the container actually holds: QuickTime and audio-only MPEG-4 files share the magic bytes
     * with MP4 video, so the major brand and compatible-brand list have to say video.
     */
    private boolean isMp4VideoBrand(byte[] header, int read) {
        List<String> audioOnly = List.of("qt  ", "M4A ", "M4B ", "M4P ", "mp4a", "3gp", "3g2");
        String major = new String(header, 8, 4, StandardCharsets.US_ASCII);
        if (audioOnly.contains(major)) return false;
        if (MP4_VIDEO_BRANDS.contains(major)) return true;
        for (int offset = 16; offset + 4 <= read; offset += 4) {
            String compatible = new String(header, offset, 4, StandardCharsets.US_ASCII);
            if (audioOnly.contains(compatible)) return false;
            if (MP4_VIDEO_BRANDS.contains(compatible)) return true;
        }
        return false;
    }

    /**
     * WebM and Matroska share the EBML magic, so the DocType string is the only thing that tells
     * them apart. It is read straight out of the header instead of parsing the whole tree.
     */
    private String ebmlDocType(Path file) throws IOException {
        byte[] head;
        try (InputStream input = Files.newInputStream(file)) {
            head = input.readNBytes(EBML_HEAD_BYTES);
        }
        for (int offset = 4; offset + 4 <= head.length; offset++) {
            if ((head[offset] & 0xff) != 0x42 || (head[offset + 1] & 0xff) != 0x82) continue;
            int length = head[offset + 2] & 0xff;
            // A one-byte EBML size descriptor carries lengths 1..127 in its low seven bits.
            if (length < 0x81 || length > 0x8f) return null;
            int size = length - 0x80;
            if (offset + 3 + size > head.length) return null;
            return new String(head, offset + 3, size, StandardCharsets.US_ASCII);
        }
        return null;
    }

    private boolean isOfficeDocument(Path file, String requiredEntry) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.getEntry("[Content_Types].xml") != null && zip.getEntry(requiredEntry) != null;
        } catch (IOException exception) { return false; }
    }

    private boolean isZip(byte[] header, int read) {
        return read >= 4 && header[0] == 'P' && header[1] == 'K'
                && ((header[2] == 3 && header[3] == 4) || (header[2] == 5 && header[3] == 6));
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
