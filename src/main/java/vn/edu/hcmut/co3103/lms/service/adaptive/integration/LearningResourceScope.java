package vn.edu.hcmut.co3103.lms.service.adaptive.integration;

import java.util.Set;
import java.util.UUID;

/**
 * Required LMS-4/LMS-5 hierarchy queries, using their canonical models.
 * Missing resources use AppException(LEARNING_RESOURCE_NOT_FOUND); inconsistent
 * class relationships use AppException(RESOURCE_CLASS_MISMATCH).
 */
public interface LearningResourceScope {

    enum ActivityKind { QUIZ, ASSIGNMENT }

    record ActivityScope(UUID classId, ActivityKind kind) { }

    record QuestionScope(UUID classId, UUID quizActivityId) { }

    /** Resolve Lesson -> Chapter -> Class; throw for a missing resource. */
    UUID classIdForLesson(UUID lessonId);

    /** Resolve Activity -> Lesson -> Chapter -> Class; throw for a missing resource. */
    ActivityScope activityScope(UUID activityId);

    /** Resolve Question -> Quiz -> Activity and its class; throw for a missing resource. */
    QuestionScope questionScope(UUID questionId);

    /** Current, complete question IDs of an existing Quiz Activity in this transaction. */
    Set<UUID> questionIdsForQuizActivity(UUID activityId);
}
