package vn.edu.hcmut.co3103.lms.service.adaptive;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.entity.SkillTopic;
import vn.edu.hcmut.co3103.lms.enums.TopicDifficulty;
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
    public SkillTopic create(UUID classId, String name, String description, TopicDifficulty difficulty) {
        Objects.requireNonNull(classId, "classId");
        access.lockForWrite(classId);
        SkillTopic topic = new SkillTopic(UUID.randomUUID(), classId, name, description, difficulty);
        validate(topic);
        if (topics.existsByClassIdAndName(classId, topic.getName())) {
            throw new IllegalArgumentException("A topic with that name already exists in the class");
        }
        return topics.saveAndFlush(topic);
    }

    public List<SkillTopic> listByClass(UUID classId) {
        Objects.requireNonNull(classId, "classId");
        access.requireReadAccess(classId);
        return topics.findAllByClassId(classId);
    }

    public SkillTopic get(UUID topicId) {
        SkillTopic topic = SkillTopicChecks.requireTopic(topics, topicId);
        access.requireReadAccess(topic.getClassId());
        return topic;
    }

    /** Replaces all editable metadata; null description/difficulty are supported. */
    @Transactional
    public SkillTopic update(UUID topicId, String name, String description, TopicDifficulty difficulty) {
        SkillTopic topic = lockTopic(topicId);
        // Validate a detached candidate before modifying the managed entity.
        SkillTopic candidate = new SkillTopic(topicId, topic.getClassId(), name, description, difficulty);
        validate(candidate);
        if (topics.existsByClassIdAndNameAndSkillTopicIdNot(
                topic.getClassId(), candidate.getName(), topicId)) {
            throw new IllegalArgumentException("A topic with that name already exists in the class");
        }
        topic.setName(candidate.getName());
        topic.setDescription(description);
        topic.setDifficultyLevel(difficulty);
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
            throw new IllegalStateException("Cannot delete a referenced skill topic");
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
        var violations = validator.validate(topic);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }
}
