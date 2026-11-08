package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AssessmentHistoryItem(
        @JsonProperty("attempt_id") UUID attemptId,
        @JsonProperty("class_id") UUID classId,
        @JsonProperty("activity_id") UUID activityId,
        @JsonProperty("activity_title") String activityTitle,
        @JsonProperty("attempt_number") Integer attemptNumber,
        String type,
        String status,
        @JsonProperty("started_at") OffsetDateTime startedAt,
        @JsonProperty("submitted_at") OffsetDateTime submittedAt,
        BigDecimal score,
        String feedback
) {
    public static AssessmentHistoryItem from(QuizAttempt attempt) {
        return new AssessmentHistoryItem(
                attempt.getId(),
                attempt.getClassMember().getClassId(),
                attempt.getQuiz().getActivity().getActivityId(),
                attempt.getQuiz().getActivity().getTitle(),
                attempt.getAttemptNumber(),
                "QUIZ",
                attempt.getStatus(),
                attempt.getStartedAt(),
                attempt.getSubmittedAt(),
                attempt.getScore(),
                null
        );
    }
}
