package vn.edu.hcmut.co3103.lms.mapper.adaptive;

import vn.edu.hcmut.co3103.lms.dto.request.adaptive.CreateSkillTopicRequest;
import vn.edu.hcmut.co3103.lms.dto.request.adaptive.UpdateSkillTopicRequest;
import vn.edu.hcmut.co3103.lms.dto.response.adaptive.SkillPrerequisiteResponse;
import vn.edu.hcmut.co3103.lms.dto.response.adaptive.SkillTopicResponse;
import vn.edu.hcmut.co3103.lms.dto.response.adaptive.TopicAssociationChangeResponse;
import vn.edu.hcmut.co3103.lms.entity.SkillPrerequisite;
import vn.edu.hcmut.co3103.lms.entity.SkillTopic;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillTopicAssociationService.TopicChange;
import vn.edu.hcmut.co3103.lms.service.adaptive.command.CreateSkillTopicCommand;
import vn.edu.hcmut.co3103.lms.service.adaptive.command.UpdateSkillTopicCommand;

/** Explicit transport mapping; no persistence or business rules. */
public final class SkillTopicApiMapper {
    private SkillTopicApiMapper() { }

    public static CreateSkillTopicCommand toCommand(CreateSkillTopicRequest request) {
        return new CreateSkillTopicCommand(request.name(), request.description(), request.difficultyLevel());
    }

    public static UpdateSkillTopicCommand toCommand(UpdateSkillTopicRequest request) {
        return new UpdateSkillTopicCommand(
                request.nameProvided(), request.name(),
                request.descriptionProvided(), request.description(),
                request.difficultyProvided(), request.difficultyLevel());
    }

    public static SkillTopicResponse toResponse(SkillTopic topic) {
        return new SkillTopicResponse(topic.getSkillTopicId(), topic.getClassId(), topic.getName(),
                topic.getDescription(), topic.getDifficultyLevel(), topic.getCreatedAt(), topic.getUpdatedAt());
    }

    public static SkillPrerequisiteResponse toResponse(SkillPrerequisite edge) {
        return new SkillPrerequisiteResponse(edge.getId().getSkillTopicId(),
                edge.getId().getPrerequisiteSkillTopicId());
    }

    public static TopicAssociationChangeResponse toResponse(TopicChange change) {
        return new TopicAssociationChangeResponse(change.previousTopicIds().stream().sorted().toList(),
                change.currentTopicIds().stream().sorted().toList());
    }
}
