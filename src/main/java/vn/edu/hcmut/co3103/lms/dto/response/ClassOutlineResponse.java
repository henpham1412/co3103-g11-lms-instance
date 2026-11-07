package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Material;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ClassOutlineResponse(
        @JsonProperty("class_id") UUID classId,
        List<ChapterItem> chapters
) {
    public record ChapterItem(@JsonProperty("chapter_id") UUID chapterId, String title, String description,
                              @JsonProperty("sequence_order") int sequenceOrder, Chapter.VisibilityStatus status,
                              List<LessonItem> lessons) { }
    public record LessonItem(@JsonProperty("lesson_id") UUID lessonId, String title, String description,
                             @JsonProperty("estimated_duration") Integer estimatedDuration,
                             @JsonProperty("sequence_order") int sequenceOrder, Chapter.VisibilityStatus status,
                             List<MaterialItem> materials, List<ActivityItem> activities) { }
    public record MaterialItem(@JsonProperty("material_id") UUID materialId, String title,
                               @JsonProperty("material_type") Material.MaterialType materialType,
                               @JsonProperty("file_url") String fileUrl, String content,
                               @JsonProperty("sequence_order") int sequenceOrder, Chapter.VisibilityStatus status,
                               @JsonProperty("created_at") Instant createdAt) { }
    public record ActivityItem(@JsonProperty("activity_id") UUID activityId, String title, String description,
                               @JsonProperty("activity_type") Activity.ActivityType activityType,
                               @JsonProperty("sequence_order") int sequenceOrder, Chapter.VisibilityStatus status,
                               @JsonProperty("created_at") Instant createdAt) { }
}
