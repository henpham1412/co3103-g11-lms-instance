package vn.edu.hcmut.co3103.lms.dto.response.adaptive;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record TopicAssociationChangeResponse(
        @JsonProperty("previous_skill_topic_ids") List<UUID> previousSkillTopicIds,
        @JsonProperty("current_skill_topic_ids") List<UUID> currentSkillTopicIds) {
    public TopicAssociationChangeResponse {
        previousSkillTopicIds = List.copyOf(previousSkillTopicIds);
        currentSkillTopicIds = List.copyOf(currentSkillTopicIds);
    }
}
