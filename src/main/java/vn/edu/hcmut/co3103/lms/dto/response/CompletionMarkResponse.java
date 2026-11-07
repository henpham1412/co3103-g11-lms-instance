package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.entity.CompletionMark;

import java.time.Instant;
import java.util.UUID;

public record CompletionMarkResponse(
        @JsonProperty("lesson_id") UUID lessonId,
        boolean completed,
        @JsonProperty("marked_at") Instant markedAt
) {
    public static CompletionMarkResponse from(CompletionMark mark) {
        return new CompletionMarkResponse(mark.getLessonId(), true, mark.getMarkedAt());
    }

    public static CompletionMarkResponse notCompleted(UUID lessonId) {
        return new CompletionMarkResponse(lessonId, false, null);
    }
}
