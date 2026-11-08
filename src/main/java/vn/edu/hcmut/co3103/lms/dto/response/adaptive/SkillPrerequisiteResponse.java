package vn.edu.hcmut.co3103.lms.dto.response.adaptive;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record SkillPrerequisiteResponse(
        @JsonProperty("skill_topic_id") UUID skillTopicId,
        @JsonProperty("prerequisite_skill_topic_id") UUID prerequisiteSkillTopicId) {
}
