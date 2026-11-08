package vn.edu.hcmut.co3103.lms.dto.request.adaptive;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.AssertTrue;
import vn.edu.hcmut.co3103.lms.enums.TopicDifficulty;

/** DOC-05 §4.5.1: omitted properties retain their values; only description is nullable. */
public final class UpdateSkillTopicRequest {
    private String name;
    private String description;
    private TopicDifficulty difficultyLevel;
    private boolean nameProvided;
    private boolean descriptionProvided;
    private boolean difficultyProvided;

    @JsonSetter(value = "name", nulls = Nulls.SET)
    public void setName(Object value) {
        nameProvided = true;
        name = nullableString(value);
    }

    @JsonSetter(value = "description", nulls = Nulls.SET)
    public void setDescription(Object value) {
        descriptionProvided = true;
        description = nullableString(value);
    }

    @JsonSetter(value = "difficulty_level", nulls = Nulls.SET)
    public void setDifficultyLevel(TopicDifficulty value) {
        difficultyProvided = true;
        difficultyLevel = value;
    }

    @JsonAnySetter
    public void rejectOtherProperties(String property, Object value) {
        // This whitelist also prevents changes to IDs, class ownership and audit fields.
        throw new IllegalArgumentException("Unsupported SkillTopic update property");
    }

    @AssertTrue(message = "SkillTopic update is invalid")
    @JsonIgnore
    public boolean isValidPatch() {
        if (!nameProvided && !descriptionProvided && !difficultyProvided) {
            return false;
        }
        if (nameProvided && (name == null || name.trim().isBlank() || name.trim().length() > 150)) {
            return false;
        }
        return !difficultyProvided || difficultyLevel != null;
    }

    public String name() { return name; }
    public String description() { return description; }
    public TopicDifficulty difficultyLevel() { return difficultyLevel; }
    public boolean nameProvided() { return nameProvided; }
    public boolean descriptionProvided() { return descriptionProvided; }
    public boolean difficultyProvided() { return difficultyProvided; }

    private static String nullableString(Object value) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw new IllegalArgumentException("SkillTopic text properties must be strings or null");
    }
}
