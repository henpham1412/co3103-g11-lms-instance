package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** Admin-only reported content. File links point to the report-scoped authorization boundary. */
public record ModerationTargetResponse(
        @JsonProperty("content_id") UUID contentId,
        @JsonProperty("class_id") UUID classId,
        @JsonProperty("lesson_id") UUID lessonId,
        @JsonProperty("author_id") UUID authorId,
        @JsonProperty("parent_id") UUID parentId,
        String title, String content,
        @JsonProperty("material_type") String materialType,
        String visibility,
        @JsonProperty("class_status") String classStatus,
        @JsonProperty("file_url") String fileUrl,
        @JsonProperty("file_available") Boolean fileAvailable
) { }
