package vn.edu.hcmut.co3103.lms.service.adaptive.command;

import vn.edu.hcmut.co3103.lms.enums.TopicDifficulty;

/** Business input, independent of HTTP transport and resource identity. */
public record CreateSkillTopicCommand(String name, String description, TopicDifficulty difficulty) {
}
