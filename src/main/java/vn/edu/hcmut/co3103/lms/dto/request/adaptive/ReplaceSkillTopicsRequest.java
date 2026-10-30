package vn.edu.hcmut.co3103.lms.dto.request.adaptive;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Shared transport shape for Tutor-managed Lesson and Assignment mappings. */
public record ReplaceSkillTopicsRequest(
        @JsonProperty("skill_topic_ids") @NotNull List<@NotNull UUID> skillTopicIds) {
}
