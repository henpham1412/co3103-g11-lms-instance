package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.entity.LastVisit;

import java.time.Instant;
import java.util.UUID;

public record LastVisitResponse(
        @JsonProperty("lesson_id") UUID lessonId,
        @JsonProperty("visited_at") Instant visitedAt
) {
    public static LastVisitResponse from(LastVisit lastVisit) {
        return new LastVisitResponse(lastVisit.getLessonId(), lastVisit.getVisitedAt());
    }
}
