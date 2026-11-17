package vn.edu.hcmut.co3103.lms.dto.request.moderation;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.edu.hcmut.co3103.lms.dto.request.StrictStringDeserializer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The DOC-05 section 3.5 report payload. The reporter, status and review outcome are never accepted
 * from the body, so any field outside that payload is captured for the service to refuse instead of
 * silently ignoring a supplied {@code reporter_id} or {@code status}.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class CreateContentReportRequest {

    @NotBlank
    @JsonProperty("content_type")
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String contentType;

    @NotNull
    @JsonProperty("content_id")
    private UUID contentId;

    @NotBlank
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String reason;

    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String description;

    private final Map<String, Object> extraFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureExtraField(String name, Object value) {
        extraFields.put(name, value);
    }

    public String content_type() { return contentType; }
    public UUID content_id() { return contentId; }
    public String reason() { return reason; }
    public String description() { return description; }
    public Map<String, Object> extraFields() { return Collections.unmodifiableMap(extraFields); }
}