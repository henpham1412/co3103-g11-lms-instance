package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record TutorClassProgressResponse(
        @JsonProperty("class_id") UUID classId,
        List<MemberSummary> members
) {
    public record MemberSummary(
            @JsonProperty("class_member_id") UUID classMemberId,
            @JsonProperty("student_id") UUID studentId,
            @JsonProperty("full_name") String fullName,
            @JsonProperty("completed_lessons") int completedLessons,
            @JsonProperty("eligible_lessons") int eligibleLessons,
            @JsonProperty("progress_percentage") int progressPercentage,
            String status
    ) { }
}
