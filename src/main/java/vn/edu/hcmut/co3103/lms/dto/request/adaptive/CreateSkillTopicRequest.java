package vn.edu.hcmut.co3103.lms.dto.request.adaptive;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vn.edu.hcmut.co3103.lms.enums.TopicDifficulty;

public record CreateSkillTopicRequest(
        @NotBlank @Size(max = 150) String name,
        String description,
        @JsonProperty("difficulty_level") @NotNull TopicDifficulty difficultyLevel) {
}
