package vn.edu.hcmut.co3103.lms.dto.response.adaptive;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.enums.MasteryLevel;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Coordinator-authorized working schema; not a claim of whole-group approval. */
public record TutorMasteryResponse(@JsonProperty("class_id") UUID classId,
                                  @JsonProperty("class_member_id") UUID classMemberId,
                                  List<Item> items) {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Item(@JsonProperty("skill_topic_id") UUID skillTopicId, String name,
                       @JsonProperty("mastery_score") BigDecimal masteryScore,
                       @JsonProperty("mastery_level") MasteryLevel masteryLevel,
                       @JsonProperty("evidence_available") boolean evidenceAvailable) { }
}
