package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.entity.Discussion;

import java.time.Instant;
import java.util.UUID;

/** The Discussion attributes of DOC-04 section 2.6.1, in the snake_case of DOC-05 section 1.6. */
public record DiscussionResponse(
        @JsonProperty("discussion_id") UUID discussionId,
        @JsonProperty("class_id") UUID classId,
        @JsonProperty("lesson_id") UUID lessonId,
        @JsonProperty("author_id") UUID authorId,
        @JsonProperty("parent_id") UUID parentId,
        String title,
        String content,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt
) {
    public static DiscussionResponse from(Discussion discussion) {
        return new DiscussionResponse(discussion.getDiscussionId(), discussion.getClassId(),
                discussion.getLessonId(), discussion.getAuthorId(), discussion.getParentId(),
                discussion.getTitle(), discussion.getContent(),
                discussion.getCreatedAt(), discussion.getUpdatedAt());
    }
}