package vn.edu.hcmut.co3103.lms.service.adaptive.command;

import vn.edu.hcmut.co3103.lms.enums.TopicDifficulty;

/** Presence is explicit and independent of Jackson; null can clear only a supplied description. */
public record UpdateSkillTopicCommand(
        boolean nameProvided, String name,
        boolean descriptionProvided, String description,
        boolean difficultyProvided, TopicDifficulty difficulty) {

    public boolean hasFields() {
        return nameProvided || descriptionProvided || difficultyProvided;
    }
}
