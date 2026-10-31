package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.entity.Material;
import java.time.Instant;
import java.util.UUID;

public final class ContentItemResponse {
    private ContentItemResponse() { }
    public record ChapterItem(@JsonProperty("chapter_id") UUID chapterId, @JsonProperty("class_id") UUID classId,
                              String title, String description, @JsonProperty("sequence_order") int sequenceOrder,
                              Chapter.VisibilityStatus status, @JsonProperty("created_at") Instant createdAt) {
        public static ChapterItem from(Chapter c) { return new ChapterItem(c.getChapterId(), c.getClassId(), c.getTitle(), c.getDescription(), c.getSequenceOrder(), c.getStatus(), c.getCreatedAt()); }
    }
    public record LessonItem(@JsonProperty("lesson_id") UUID lessonId, @JsonProperty("chapter_id") UUID chapterId,
                             String title, String description, @JsonProperty("estimated_duration") Integer estimatedDuration,
                             @JsonProperty("sequence_order") int sequenceOrder, Chapter.VisibilityStatus status,
                             @JsonProperty("created_at") Instant createdAt) {
        public static LessonItem from(Lesson l) { return new LessonItem(l.getLessonId(), l.getChapterId(), l.getTitle(), l.getDescription(), l.getEstimatedDuration(), l.getSequenceOrder(), l.getStatus(), l.getCreatedAt()); }
    }
    public record MaterialItem(@JsonProperty("material_id") UUID materialId, @JsonProperty("lesson_id") UUID lessonId,
                               String title, @JsonProperty("material_type") Material.MaterialType materialType,
                               @JsonProperty("file_url") String fileUrl, String content,
                               @JsonProperty("sequence_order") int sequenceOrder, Chapter.VisibilityStatus status,
                               @JsonProperty("created_at") Instant createdAt) {
        public static MaterialItem from(Material m) { return new MaterialItem(m.getMaterialId(), m.getLessonId(), m.getTitle(), m.getMaterialType(), m.getFileUrl(), m.getContent(), m.getSequenceOrder(), m.getStatus(), m.getCreatedAt()); }
    }
}
