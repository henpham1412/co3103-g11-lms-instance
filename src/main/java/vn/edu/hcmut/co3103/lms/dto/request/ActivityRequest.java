package vn.edu.hcmut.co3103.lms.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

/**
 * Placement fields only: Learning Content owns an Activity's position, order and visibility, while
 * the Quiz or Assignment configuration behind {@code activity_type} belongs to the Assessment
 * module. Anything else in the body is captured rather than ignored, so a request that tries to
 * configure the assessment in the same call is refused instead of half-applied.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ActivityRequest {

    @NotBlank
    private String title;

    private String description;

    @JsonProperty("activity_type")
    @NotNull
    private Activity.ActivityType activityType;

    @JsonProperty("sequence_order")
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    @Min(0)
    private Integer sequenceOrder;

    private Chapter.VisibilityStatus status;

    private final Map<String, Object> extraFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureExtraField(String name, Object value) {
        extraFields.put(name, value);
    }

    public String title() { return title; }
    public String description() { return description; }
    public Activity.ActivityType activity_type() { return activityType; }
    public Integer sequence_order() { return sequenceOrder; }
    public Chapter.VisibilityStatus status() { return status; }
    public Map<String, Object> extraFields() { return Collections.unmodifiableMap(extraFields); }

    public boolean carriesAssessmentConfiguration() {
        return extraFields.containsKey("quiz") || extraFields.containsKey("assignment");
    }
}
