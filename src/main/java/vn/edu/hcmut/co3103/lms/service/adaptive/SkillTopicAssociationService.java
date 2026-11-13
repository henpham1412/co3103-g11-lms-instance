package vn.edu.hcmut.co3103.lms.service.adaptive;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.entity.ActivitySkillTopic;
import vn.edu.hcmut.co3103.lms.entity.ActivitySkillTopicId;
import vn.edu.hcmut.co3103.lms.entity.LessonSkillTopic;
import vn.edu.hcmut.co3103.lms.entity.LessonSkillTopicId;
import vn.edu.hcmut.co3103.lms.entity.QuestionSkillTopic;
import vn.edu.hcmut.co3103.lms.entity.QuestionSkillTopicId;
import vn.edu.hcmut.co3103.lms.entity.SkillTopic;
import vn.edu.hcmut.co3103.lms.repository.adaptive.ActivitySkillTopicRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.LessonSkillTopicRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.QuestionSkillTopicRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.SkillTopicRepository;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.ClassAccess;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.LearningResourceScope;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.LearningResourceScope.ActivityKind;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Association writes only, never Question CRUD or grading. Register as a
 * transactional Spring bean only after real hierarchy/access adapters exist.
 */
@Service
@Transactional(readOnly = true)
public class SkillTopicAssociationService {

    private final SkillTopicRepository topics;
    private final LessonSkillTopicRepository lessons;
    private final ActivitySkillTopicRepository activities;
    private final QuestionSkillTopicRepository questions;
    private final ClassAccess access;
    private final LearningResourceScope scope;
    private final MasteryRecalculationService mastery;

    public SkillTopicAssociationService(SkillTopicRepository topics, LessonSkillTopicRepository lessons,
                                        ActivitySkillTopicRepository activities,
                                        QuestionSkillTopicRepository questions,
                                        ClassAccess access, LearningResourceScope scope,
                                        MasteryRecalculationService mastery) {
        this.topics = topics;
        this.lessons = lessons;
        this.activities = activities;
        this.questions = questions;
        this.access = access;
        this.scope = scope;
        this.mastery = mastery;
    }

    /** Old/new sets allow future LMS-9 integration without calculating mastery here. */
    public record TopicChange(Set<UUID> previousTopicIds, Set<UUID> currentTopicIds) {
        public TopicChange {
            previousTopicIds = Set.copyOf(previousTopicIds);
            currentTopicIds = Set.copyOf(currentTopicIds);
        }
    }

    public List<SkillTopic> getLessonTopics(UUID lessonId) {
        SkillTopicChecks.requireId(lessonId);
        UUID classId = scope.classIdForLesson(lessonId);
        access.requireReadAccess(classId);
        Set<UUID> ids = lessons.findAllById_LessonId(lessonId).stream()
                .map(link -> link.getId().getSkillTopicId()).collect(Collectors.toSet());
        return SkillTopicChecks.requireTopicsInClass(topics, ids, classId);
    }

    @Transactional
    public TopicChange replaceLessonTopics(UUID lessonId, Collection<UUID> topicIds) {
        SkillTopicChecks.requireId(lessonId);
        Set<UUID> requested = SkillTopicChecks.uniqueIds(topicIds);
        UUID classId = scope.classIdForLesson(lessonId);
        access.lockForWrite(classId);
        requireSameScope(classId, scope.classIdForLesson(lessonId));
        SkillTopicChecks.requireTopicsInClass(topics, requested, classId);
        List<LessonSkillTopic> existing = lessons.findAllById_LessonId(lessonId);
        Set<UUID> previous = existing.stream().map(link -> link.getId().getSkillTopicId())
                .collect(Collectors.toSet());
        lessons.deleteAll(existing.stream()
                .filter(link -> !requested.contains(link.getId().getSkillTopicId())).toList());
        lessons.saveAll(requested.stream().filter(id -> !previous.contains(id))
                .map(id -> new LessonSkillTopic(new LessonSkillTopicId(lessonId, id))).toList());
        lessons.flush();
        mastery.synchronizeLesson(lessonId);
        return new TopicChange(previous, requested);
    }

    public List<SkillTopic> getActivityTopics(UUID activityId) {
        SkillTopicChecks.requireId(activityId);
        var activity = scope.activityScope(activityId);
        access.requireReadAccess(activity.classId());
        // Quiz topics are authoritative from Questions, not an independently editable set.
        Set<UUID> ids = activity.kind() == ActivityKind.QUIZ ? quizTopicIds(activityId)
                : activityTopicIds(activityId);
        return SkillTopicChecks.requireTopicsInClass(topics, ids, activity.classId());
    }

    @Transactional
    public TopicChange replaceActivityTopics(UUID activityId, Collection<UUID> topicIds) {
        SkillTopicChecks.requireId(activityId);
        Set<UUID> requested = SkillTopicChecks.uniqueIds(topicIds);
        var activity = scope.activityScope(activityId);
        access.lockForWrite(activity.classId());
        if (!activity.equals(scope.activityScope(activityId))) {
            throw new AppException(ErrorCode.RESOURCE_STATE_CONFLICT);
        }
        if (activity.kind() != ActivityKind.ASSIGNMENT) {
            throw new AppException(ErrorCode.QUIZ_TOPICS_DERIVED);
        }
        SkillTopicChecks.requireTopicsInClass(topics, requested, activity.classId());
        return replaceActivityMappings(activityId, requested);
    }

    public List<SkillTopic> getQuestionTopics(UUID questionId) {
        SkillTopicChecks.requireId(questionId);
        var question = scope.questionScope(questionId);
        access.requireReadAccess(question.classId());
        Set<UUID> ids = questions.findAllById_QuestionId(questionId).stream()
                .map(link -> link.getId().getSkillTopicId()).collect(Collectors.toSet());
        return SkillTopicChecks.requireTopicsInClass(topics, ids, question.classId());
    }

    /**
     * LMS-5 integration hook: must join its existing Question write transaction.
     * It does not create/update Questions or bypass LMS-5 quiz-locking rules.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TopicChange replaceQuestionTopics(UUID questionId, Collection<UUID> topicIds) {
        SkillTopicChecks.requireId(questionId);
        Set<UUID> requested = SkillTopicChecks.uniqueIds(topicIds);
        var question = scope.questionScope(questionId);
        access.lockForWrite(question.classId());
        if (!question.equals(scope.questionScope(questionId))) {
            throw new AppException(ErrorCode.RESOURCE_STATE_CONFLICT);
        }
        var activity = scope.activityScope(question.quizActivityId());
        requireSameScope(question.classId(), activity.classId());
        if (activity.kind() != ActivityKind.QUIZ) {
            throw new AppException(ErrorCode.INVALID_TOPIC_ASSOCIATION);
        }
        if (!scope.questionIdsForQuizActivity(question.quizActivityId()).contains(questionId)) {
            throw new AppException(ErrorCode.RESOURCE_STATE_CONFLICT);
        }
        SkillTopicChecks.requireTopicsInClass(topics, requested, question.classId());
        List<QuestionSkillTopic> existing = questions.findAllById_QuestionId(questionId);
        Set<UUID> previous = existing.stream().map(link -> link.getId().getSkillTopicId())
                .collect(Collectors.toSet());
        questions.deleteAll(existing.stream()
                .filter(link -> !requested.contains(link.getId().getSkillTopicId())).toList());
        questions.saveAll(requested.stream().filter(id -> !previous.contains(id))
                .map(id -> new QuestionSkillTopic(new QuestionSkillTopicId(questionId, id))).toList());
        questions.flush();
        synchronizeQuizMappings(question.quizActivityId(), question.classId());
        return new TopicChange(previous, requested);
    }

    /** LMS-5 must also call this after permitted Question removal or other mapping changes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public TopicChange synchronizeQuizTopics(UUID activityId) {
        SkillTopicChecks.requireId(activityId);
        var activity = scope.activityScope(activityId);
        access.lockForWrite(activity.classId());
        if (!activity.equals(scope.activityScope(activityId))) {
            throw new AppException(ErrorCode.RESOURCE_STATE_CONFLICT);
        }
        if (activity.kind() != ActivityKind.QUIZ) {
            throw new AppException(ErrorCode.INVALID_TOPIC_ASSOCIATION);
        }
        return synchronizeQuizMappings(activityId, activity.classId());
    }

    private TopicChange synchronizeQuizMappings(UUID activityId, UUID classId) {
        Set<UUID> union = quizTopicIds(activityId);
        SkillTopicChecks.requireTopicsInClass(topics, union, classId);
        return replaceActivityMappings(activityId, union);
    }

    private Set<UUID> quizTopicIds(UUID activityId) {
        Set<UUID> questionIds = scope.questionIdsForQuizActivity(activityId);
        if (questionIds.isEmpty()) {
            return Set.of();
        }
        return questions.findAllById_QuestionIdIn(questionIds).stream()
                .map(link -> link.getId().getSkillTopicId()).collect(Collectors.toSet());
    }

    private Set<UUID> activityTopicIds(UUID activityId) {
        return activities.findAllById_ActivityId(activityId).stream()
                .map(link -> link.getId().getSkillTopicId()).collect(Collectors.toSet());
    }

    private TopicChange replaceActivityMappings(UUID activityId, Set<UUID> requested) {
        List<ActivitySkillTopic> existing = activities.findAllById_ActivityId(activityId);
        Set<UUID> previous = existing.stream().map(link -> link.getId().getSkillTopicId())
                .collect(Collectors.toSet());
        activities.deleteAll(existing.stream()
                .filter(link -> !requested.contains(link.getId().getSkillTopicId())).toList());
        activities.saveAll(requested.stream().filter(id -> !previous.contains(id))
                .map(id -> new ActivitySkillTopic(new ActivitySkillTopicId(activityId, id))).toList());
        activities.flush();
        return new TopicChange(previous, requested);
    }

    private void requireSameScope(UUID expected, UUID actual) {
        if (!expected.equals(actual)) {
            throw new AppException(ErrorCode.RESOURCE_CLASS_MISMATCH);
        }
    }
}
