package vn.edu.hcmut.co3103.lms.service.assessment;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Explicit formats already supported by file assignments; filenames are never content proof. */
final class AssignmentFileTypes {
    private static final Map<String, String> TYPES = Map.of(
            "pdf", "application/pdf", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "txt", "text/plain", "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg", "zip", "application/zip");
    private AssignmentFileTypes() { }
    static String mime(String token) {
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(".")) normalized = normalized.substring(1);
        String mime = TYPES.get(normalized);
        if (mime != null) return mime;
        if (TYPES.containsValue(normalized)) return normalized;
        throw new IllegalArgumentException("Unsupported assignment file type");
    }
    static Set<String> allowed(String configured) {
        return configured == null || configured.isBlank() ? Set.copyOf(TYPES.values())
                : Arrays.stream(configured.split(",", -1)).map(AssignmentFileTypes::mime).collect(Collectors.toUnmodifiableSet());
    }
    static String extension(String name) {
        if (name == null || !name.contains(".")) throw new IllegalArgumentException("Missing file extension");
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!TYPES.containsKey(extension)) throw new IllegalArgumentException("Unsupported file extension");
        return extension;
    }
}
