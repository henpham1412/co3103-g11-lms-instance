package vn.edu.hcmut.co3103.lms.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum TopicDifficulty {
    EASY,
    MEDIUM,
    HARD;

    /** JSON uses symbolic strings only; database codes are handled by the JPA converter. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TopicDifficulty fromJson(Object value) {
        if (value instanceof String name) {
            return switch (name) {
                case "EASY" -> EASY;
                case "MEDIUM" -> MEDIUM;
                case "HARD" -> HARD;
                default -> throw new IllegalArgumentException("Invalid SkillTopic difficulty");
            };
        }
        throw new IllegalArgumentException("SkillTopic difficulty must be a symbolic string");
    }
}
