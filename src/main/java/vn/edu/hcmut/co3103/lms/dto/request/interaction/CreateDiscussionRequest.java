package vn.edu.hcmut.co3103.lms.dto.request.interaction;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.edu.hcmut.co3103.lms.dto.request.StrictStringDeserializer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A root thread. Authorship is never accepted from the body: any field outside the documented
 * create-thread payload is captured so the service can refuse it instead of silently ignoring a
 * supplied {@code author_id}, {@code class_id} or {@code parent_id}.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class CreateDiscussionRequest {

    @JsonProperty("lesson_id")
    private UUID lessonId;

    @NotBlank
    @Size(max = 255)
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String title;

    @NotBlank
    @JsonDeserialize(using = StrictStringDeserializer.class)
    private String content;

    private final Map<String, Object> extraFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void captureExtraField(String name, Object value) {
        extraFields.put(name, value);
    }

    public UUID lesson_id() { return lessonId; }
    public String title() { return title; }
    public String content() { return content; }
    public Map<String, Object> extraFields() { return Collections.unmodifiableMap(extraFields); }
}