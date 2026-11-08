package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record TutorMemberProgressResponse(
        @JsonProperty("class_id") UUID classId,
        @JsonProperty("class_member_id") UUID classMemberId,
        @JsonProperty("student_id") UUID studentId,
        @JsonProperty("membership_status") String membershipStatus,
        @JsonProperty("completed_lessons") int completedLessons,
        @JsonProperty("eligible_lessons") int eligibleLessons,
        @JsonProperty("progress_percentage") int progressPercentage,
        String status,
        List<ClassProgressResponse.ChapterProgress> chapters,
        @JsonProperty("assessment_outcomes") List<AssessmentHistoryItem> assessmentOutcomes,
        @JsonProperty("skill_mastery") List<SkillMastery> skillMastery
) {
    public record SkillMastery(
            @JsonProperty("skill_topic_id") UUID skillTopicId,
            String name,
            String level
    ) { }
}
