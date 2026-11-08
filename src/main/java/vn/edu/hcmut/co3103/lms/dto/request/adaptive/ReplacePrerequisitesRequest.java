package vn.edu.hcmut.co3103.lms.dto.request.adaptive;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** Empty means remove all outgoing prerequisites; null is invalid. */
public record ReplacePrerequisitesRequest(
        @JsonProperty("prerequisite_skill_topic_ids") @NotNull List<@NotNull UUID> prerequisiteSkillTopicIds) {
}
