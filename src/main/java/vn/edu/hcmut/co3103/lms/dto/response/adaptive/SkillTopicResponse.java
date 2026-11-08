package vn.edu.hcmut.co3103.lms.dto.response.adaptive;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.enums.TopicDifficulty;

import java.time.Instant;
import java.util.UUID;

public record SkillTopicResponse(
        @JsonProperty("skill_topic_id") UUID skillTopicId,
        @JsonProperty("class_id") UUID classId,
        String name,
        String description,
        @JsonProperty("difficulty_level") TopicDifficulty difficultyLevel,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt) {
}
