package vn.edu.hcmut.co3103.lms.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class QuestionUpdateRequest {
    private String questionType;
    private String content;
    private BigDecimal pointsAwarded;
    private BigDecimal pointsDeducted;
    private Integer orderIndex;
    private List<Option> options;
    private List<UUID> skillTopicIds;
    private boolean questionTypeProvided, contentProvided, pointsAwardedProvided;
    private boolean pointsDeductedProvided, orderIndexProvided, optionsProvided;
    private boolean skillTopicIdsProvided;

    // note to self: @jsonproperty is required for deserialization of snake_case fields to camelCase fields
    @JsonProperty("question_type") public void setQuestionType(String v) { questionTypeProvided = true; questionType = v; }
    public void setContent(String v) { contentProvided = true; content = v; }
    @JsonProperty("points_awarded") public void setPointsAwarded(BigDecimal v) { pointsAwardedProvided = true; pointsAwarded = v; }
    @JsonProperty("points_deducted") public void setPointsDeducted(BigDecimal v) { pointsDeductedProvided = true; pointsDeducted = v; }
    @JsonDeserialize(using = AssessmentIntegerDeserializer.class) @JsonProperty("order_index") public void setOrderIndex(Integer v) { orderIndexProvided = true; orderIndex = v; }
    public void setOptions(List<Option> v) { optionsProvided = true; options = v; }
    @JsonProperty("skill_topic_ids") public void setSkillTopicIds(List<UUID> v) { skillTopicIdsProvided = true; skillTopicIds = v; }

    @SuppressWarnings("unused") // hack rejecting unknown fields in request body
    @JsonAnySetter public void rejectUnknownField(String name, Object value) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION");
    }

    public String questionType() { return questionType; }
    public String content() { return content; }
    public BigDecimal pointsAwarded() { return pointsAwarded; }
    public BigDecimal pointsDeducted() { return pointsDeducted; }
    public Integer orderIndex() { return orderIndex; }
    public List<Option> options() { return options; }
    public List<UUID> skillTopicIds() { return skillTopicIds; }
    public boolean questionTypeProvided() { return questionTypeProvided; }
    public boolean contentProvided() { return contentProvided; }
    public boolean pointsAwardedProvided() { return pointsAwardedProvided; }
    public boolean pointsDeductedProvided() { return pointsDeductedProvided; }
    public boolean orderIndexProvided() { return orderIndexProvided; }
    public boolean optionsProvided() { return optionsProvided; }
    public boolean skillTopicIdsProvided() { return skillTopicIdsProvided; }

    public record Option(
            @JsonProperty("option_id") UUID optionId,
            String content,
            @JsonProperty("is_correct") Boolean isCorrect,
            @JsonDeserialize(using = AssessmentIntegerDeserializer.class) @JsonProperty("order_index") Integer orderIndex
    ) {}
}