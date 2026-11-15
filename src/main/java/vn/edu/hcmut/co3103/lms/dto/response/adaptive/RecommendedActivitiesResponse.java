package vn.edu.hcmut.co3103.lms.dto.response.adaptive;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.enums.MasteryLevel;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record RecommendedActivitiesResponse(
        @JsonProperty("class_id") UUID classId,
        Recommendation recommendation,
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {
    public record Recommendation(
            @JsonProperty("activity_id") UUID activityId,
            @JsonProperty("activity_type") String activityType,
            String title, String reason,
            @JsonProperty("skill_topics") List<Topic> skillTopics) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Topic(@JsonProperty("skill_topic_id") UUID skillTopicId, String name,
                        @JsonProperty("mastery_level") MasteryLevel masteryLevel) { }
}
