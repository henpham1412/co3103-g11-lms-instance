package vn.edu.hcmut.co3103.lms.dto.request.interaction;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.edu.hcmut.co3103.lms.dto.request.StrictStringDeserializer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A reply inherits its class, lesson and parent from the path, so none of them is accepted here.
 * DOC-05 section 3.3 requires content and does not require a title on a reply.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class CreateReplyRequest {

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

    public String title() { return title; }
    public String content() { return content; }
    public Map<String, Object> extraFields() { return Collections.unmodifiableMap(extraFields); }
}