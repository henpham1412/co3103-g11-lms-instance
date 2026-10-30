package vn.edu.hcmut.co3103.lms.service.adaptive.command;

import vn.edu.hcmut.co3103.lms.enums.TopicDifficulty;

/** Replaces all editable metadata; null description clears it. Not a PATCH DTO. */
public record UpdateSkillTopicCommand(String name, String description, TopicDifficulty difficulty) {
}
