package vn.edu.hcmut.co3103.lms.service.adaptive;

import jakarta.validation.Validator;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.entity.SkillTopic;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.service.adaptive.command.CreateSkillTopicCommand;
import vn.edu.hcmut.co3103.lms.service.adaptive.command.UpdateSkillTopicCommand;
import vn.edu.hcmut.co3103.lms.repository.adaptive.ActivitySkillTopicRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.LessonSkillTopicRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.QuestionSkillTopicRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.SkillPrerequisiteRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.SkillTopicRepository;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.ClassAccess;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Register as a transactional Spring bean only with a real ClassAccess adapter. */
@Transactional(readOnly = true)
public class SkillTopicService {

    private final SkillTopicRepository topics;
    private final SkillPrerequisiteRepository prerequisites;
    private final LessonSkillTopicRepository lessons;
    private final ActivitySkillTopicRepository activities;
    private final QuestionSkillTopicRepository questions;
    private final ClassAccess access;
    private final Validator validator;

    public SkillTopicService(SkillTopicRepository topics, SkillPrerequisiteRepository prerequisites,
                             LessonSkillTopicRepository lessons, ActivitySkillTopicRepository activities,
                             QuestionSkillTopicRepository questions, ClassAccess access, Validator validator) {
        this.topics = topics;
        this.prerequisites = prerequisites;
        this.lessons = lessons;
        this.activities = activities;
        this.questions = questions;
        this.access = access;
        this.validator = validator;
    }

    @Transactional
    public SkillTopic create(UUID classId, CreateSkillTopicCommand command) {
        SkillTopicChecks.requireId(classId);
        if (command == null) {
            throw new AppException(ErrorCode.INVALID_SKILL_TOPIC);
        }
        access.lockForWrite(classId);
        SkillTopic topic = new SkillTopic(UUID.randomUUID(), classId,
                command.name(), command.description(), command.difficulty());
        validate(topic);
        if (topics.existsByClassIdAndName(classId, topic.getName())) {
            throw new AppException(ErrorCode.SKILL_TOPIC_NAME_DUPLICATE);
        }
        return topics.saveAndFlush(topic);
    }

    public List<SkillTopic> listByClass(UUID classId) {
        SkillTopicChecks.requireId(classId);
        access.requireReadAccess(classId);
        return topics.findAllByClassId(classId);
    }

    public SkillTopic get(UUID topicId) {
        SkillTopic topic = SkillTopicChecks.requireTopic(topics, topicId);
        access.requireReadAccess(topic.getClassId());
        return topic;
    }

    /** Partial update: validate the merged candidate before touching the managed entity. */
    @Transactional
    public SkillTopic update(UUID topicId, UpdateSkillTopicCommand command) {
        if (command == null || !command.hasFields()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        SkillTopic topic = lockTopic(topicId);
        // Validate a detached candidate before modifying the managed entity.
        SkillTopic candidate = new SkillTopic(topicId, topic.getClassId(),
                command.nameProvided() ? command.name() : topic.getName(),
                command.descriptionProvided() ? command.description() : topic.getDescription(),
                command.difficultyProvided() ? command.difficulty() : topic.getDifficultyLevel());
        validate(candidate, ErrorCode.VALIDATION_ERROR);
        boolean nameChanged = !Objects.equals(topic.getName(), candidate.getName());
        boolean descriptionChanged = !Objects.equals(topic.getDescription(), candidate.getDescription());
        boolean difficultyChanged = topic.getDifficultyLevel() != candidate.getDifficultyLevel();
        if (!nameChanged && !descriptionChanged && !difficultyChanged) {
            return topic;
        }
        if (nameChanged && topics.existsByClassIdAndNameAndSkillTopicIdNot(
                topic.getClassId(), candidate.getName(), topicId)) {
            throw new AppException(ErrorCode.SKILL_TOPIC_NAME_DUPLICATE);
        }
        if (nameChanged) topic.setName(candidate.getName());
        if (descriptionChanged) topic.setDescription(candidate.getDescription());
        if (difficultyChanged) topic.setDifficultyLevel(candidate.getDifficultyLevel());
        return topics.saveAndFlush(topic);
    }

    @Transactional
    public void delete(UUID topicId) {
        SkillTopic topic = lockTopic(topicId);
        if (!prerequisites.findAllById_SkillTopicId(topicId).isEmpty()
                || prerequisites.existsById_PrerequisiteSkillTopicId(topicId)
                || lessons.existsById_SkillTopicId(topicId)
                || activities.existsById_SkillTopicId(topicId)
                || questions.existsById_SkillTopicId(topicId)) {
            throw new AppException(ErrorCode.SKILL_TOPIC_REFERENCED);
        }
        // Additional/future history references must remain protected by restrictive FKs.
        topics.delete(topic);
        topics.flush();
    }

    private SkillTopic lockTopic(UUID topicId) {
        UUID classId = SkillTopicChecks.requireClassId(topics, topicId);
        access.lockForWrite(classId);
        return SkillTopicChecks.requireTopic(topics, topicId);
    }

    private void validate(SkillTopic topic) {
        validate(topic, ErrorCode.INVALID_SKILL_TOPIC);
    }

    private void validate(SkillTopic topic, ErrorCode errorCode) {
        var violations = validator.validate(topic);
        if (!violations.isEmpty()) {
            throw new AppException(errorCode);
        }
    }
}
