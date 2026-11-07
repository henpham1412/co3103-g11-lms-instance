package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ClassProgressResponse(
        @JsonProperty("class_id") UUID classId,
        @JsonProperty("completed_lessons") int completedLessons,
        @JsonProperty("eligible_lessons") int eligibleLessons,
        @JsonProperty("progress_percentage") int progressPercentage,
        String status,
        List<ChapterProgress> chapters,
        @JsonProperty("resume_target") ResumeTarget resumeTarget
) {
    public record ChapterProgress(
            @JsonProperty("chapter_id") UUID chapterId,
            String title,
            @JsonProperty("completed_lessons") int completedLessons,
            @JsonProperty("eligible_lessons") int eligibleLessons,
            @JsonProperty("progress_percentage") int progressPercentage,
            List<LessonProgress> lessons
    ) { }

    public record LessonProgress(
            @JsonProperty("lesson_id") UUID lessonId,
            String title,
            @JsonProperty("sequence_order") int sequenceOrder,
            boolean completed,
            @JsonProperty("marked_at") Instant markedAt,
            @JsonProperty("assessment_statuses") List<AssessmentStatus> assessmentStatuses
    ) { }

    public record AssessmentStatus(
            @JsonProperty("activity_id") UUID activityId,
            String title,
            @JsonProperty("activity_type") String activityType,
            String status,
            List<AttemptStatus> attempts
    ) { }

    public record AttemptStatus(
            @JsonProperty("attempt_id") UUID attemptId,
            @JsonProperty("attempt_number") Integer attemptNumber,
            String status,
            @JsonProperty("started_at") OffsetDateTime startedAt,
            @JsonProperty("submitted_at") OffsetDateTime submittedAt,
            BigDecimal score
    ) { }

    public record ResumeTarget(
            @JsonProperty("target_type") String targetType,
            @JsonProperty("target_id") UUID targetId,
            String reason
    ) { }
}
