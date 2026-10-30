package vn.edu.hcmut.co3103.lms.service.adaptive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.edu.hcmut.co3103.lms.exception.AppException;

import static vn.edu.hcmut.co3103.lms.exception.ErrorCode.*;
import static vn.edu.hcmut.co3103.lms.service.adaptive.ErrorAssertions.assertError;
import vn.edu.hcmut.co3103.lms.entity.*;
import vn.edu.hcmut.co3103.lms.repository.adaptive.*;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.ClassAccess;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.LearningResourceScope;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.LearningResourceScope.ActivityKind;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.LearningResourceScope.ActivityScope;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.LearningResourceScope.QuestionScope;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkillTopicAssociationServiceTest {
    private static final UUID CLASS_ID = UUID.randomUUID();
    private static final UUID LESSON = UUID.randomUUID();
    private static final UUID ACTIVITY = UUID.randomUUID();
    private static final UUID Q1 = UUID.randomUUID();
    private static final UUID Q2 = UUID.randomUUID();
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private SkillTopicRepository topics;
    private LessonSkillTopicRepository lessons;
    private ActivitySkillTopicRepository activities;
    private QuestionSkillTopicRepository questions;
    private ClassAccess access;
    private LearningResourceScope scope;
    private SkillTopicAssociationService service;

    @BeforeEach
    void setUp() {
        topics = mock(SkillTopicRepository.class);
        lessons = mock(LessonSkillTopicRepository.class);
        activities = mock(ActivitySkillTopicRepository.class);
        questions = mock(QuestionSkillTopicRepository.class);
        access = mock(ClassAccess.class);
        scope = mock(LearningResourceScope.class);
        service = new SkillTopicAssociationService(topics, lessons, activities, questions, access, scope);
        when(scope.classIdForLesson(LESSON)).thenReturn(CLASS_ID);
        when(scope.activityScope(ACTIVITY)).thenReturn(new ActivityScope(CLASS_ID, ActivityKind.QUIZ));
        when(scope.questionScope(Q1)).thenReturn(new QuestionScope(CLASS_ID, ACTIVITY));
        when(scope.questionIdsForQuizActivity(ACTIVITY)).thenReturn(Set.of(Q1, Q2));
        when(topics.findAllById(any())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return ids.stream().filter(id -> id.equals(A) || id.equals(B))
                    .map(id -> new SkillTopic(id, CLASS_ID, id.toString(), null, null)).toList();
        });
    }

    @Test
    void lessonReplacementValidatesBeforeWritingAndReturnsOldNewSets() {
        LessonSkillTopic old = new LessonSkillTopic(new LessonSkillTopicId(LESSON, A));
        when(lessons.findAllById_LessonId(LESSON)).thenReturn(List.of(old));
        var change = service.replaceLessonTopics(LESSON, List.of(B));
        assertEquals(Set.of(A), change.previousTopicIds());
        assertEquals(Set.of(B), change.currentTopicIds());
        verify(lessons).deleteAll(List.of(old));
        verify(lessons).saveAll(argThat(links -> ((List<LessonSkillTopic>) links).getFirst()
                .getId().equals(new LessonSkillTopicId(LESSON, B))));
        var order = inOrder(access, topics, lessons);
        order.verify(access).lockForWrite(CLASS_ID);
        order.verify(topics).findAllById(Set.of(B));
        order.verify(lessons).findAllById_LessonId(LESSON);
        order.verify(lessons).deleteAll(anyIterable());
    }

    @Test
    void emptyLessonReplacementClearsMappings() {
        LessonSkillTopic old = new LessonSkillTopic(new LessonSkillTopicId(LESSON, A));
        when(lessons.findAllById_LessonId(LESSON)).thenReturn(List.of(old));
        assertEquals(Set.of(), service.replaceLessonTopics(LESSON, List.of()).currentTopicIds());
        verify(lessons).deleteAll(List.of(old));
    }

    @Test
    void invalidLessonTopicsCannotCausePartialWrites() {
        assertError(SKILL_TOPIC_NOT_FOUND,
                () -> service.replaceLessonTopics(LESSON, List.of(A, UUID.randomUUID())));
        verify(lessons, never()).deleteAll(anyIterable());
        verify(lessons, never()).saveAll(anyIterable());
    }

    @Test
    void rejectsCrossClassMappingsBeforeWrites() {
        when(topics.findAllById(Set.of(A))).thenReturn(List.of(
                new SkillTopic(A, UUID.randomUUID(), "Foreign", null, null)));
        assertError(RESOURCE_CLASS_MISMATCH, () -> service.replaceLessonTopics(LESSON, List.of(A)));
        verify(lessons, never()).deleteAll(anyIterable());
        when(scope.activityScope(ACTIVITY)).thenReturn(new ActivityScope(CLASS_ID, ActivityKind.ASSIGNMENT));
        assertError(RESOURCE_CLASS_MISMATCH, () -> service.replaceActivityTopics(ACTIVITY, List.of(A)));
        verify(activities, never()).deleteAll(anyIterable());
        when(scope.activityScope(ACTIVITY)).thenReturn(new ActivityScope(CLASS_ID, ActivityKind.QUIZ));
        assertError(RESOURCE_CLASS_MISMATCH, () -> service.replaceQuestionTopics(Q1, List.of(A)));
        verify(questions, never()).deleteAll(anyIterable());
    }

    @Test
    void assignmentTopicsAreIndependentlyManaged() {
        when(scope.activityScope(ACTIVITY)).thenReturn(new ActivityScope(CLASS_ID, ActivityKind.ASSIGNMENT));
        ActivitySkillTopic old = new ActivitySkillTopic(new ActivitySkillTopicId(ACTIVITY, A));
        when(activities.findAllById_ActivityId(ACTIVITY)).thenReturn(List.of(old));
        assertEquals(Set.of(B), service.replaceActivityTopics(ACTIVITY, List.of(B)).currentTopicIds());
        verify(activities).deleteAll(List.of(old));
        verifyNoInteractions(questions);
    }

    @Test
    void rejectsDirectReplacementOfQuizTopics() {
        assertError(QUIZ_TOPICS_DERIVED, () -> service.replaceActivityTopics(ACTIVITY, List.of(A)));
        verify(activities, never()).deleteAll(anyIterable());
        verify(activities, never()).saveAll(anyIterable());
    }

    @Test
    void questionReplacementFlushesBeforeRebuildingDeduplicatedQuizUnion() {
        QuestionSkillTopic old = questionLink(Q1, A);
        when(questions.findAllById_QuestionId(Q1)).thenReturn(List.of(old));
        when(questions.findAllById_QuestionIdIn(Set.of(Q1, Q2)))
                .thenReturn(List.of(questionLink(Q1, B), questionLink(Q2, A), questionLink(Q2, B)));
        var change = service.replaceQuestionTopics(Q1, List.of(B));
        assertEquals(Set.of(A), change.previousTopicIds());
        assertEquals(Set.of(B), change.currentTopicIds());
        verify(questions).deleteAll(List.of(old));
        var order = inOrder(questions, activities);
        order.verify(questions).flush();
        order.verify(questions).findAllById_QuestionIdIn(Set.of(Q1, Q2));
        order.verify(activities).saveAll(argThat(links -> {
            List<ActivitySkillTopic> list = (List<ActivitySkillTopic>) links;
            return list.size() == 2 && list.stream().map(link -> link.getId().getSkillTopicId())
                    .collect(java.util.stream.Collectors.toSet()).equals(Set.of(A, B));
        }));
    }

    @Test
    void clearingOneQuestionRetainsTopicsUsedByOtherQuestions() {
        when(questions.findAllById_QuestionId(Q1)).thenReturn(List.of(questionLink(Q1, A)));
        when(questions.findAllById_QuestionIdIn(Set.of(Q1, Q2))).thenReturn(List.of(questionLink(Q2, A)));
        ActivitySkillTopic old = new ActivitySkillTopic(new ActivitySkillTopicId(ACTIVITY, A));
        when(activities.findAllById_ActivityId(ACTIVITY)).thenReturn(List.of(old));
        service.replaceQuestionTopics(Q1, List.of());
        verify(activities).deleteAll(List.of());
        verify(activities).saveAll(List.of());
    }

    @Test
    void synchronizeAfterLastQuestionRemovalClearsQuizTopics() {
        when(scope.questionIdsForQuizActivity(ACTIVITY)).thenReturn(Set.of());
        ActivitySkillTopic old = new ActivitySkillTopic(new ActivitySkillTopicId(ACTIVITY, A));
        when(activities.findAllById_ActivityId(ACTIVITY)).thenReturn(List.of(old));
        assertEquals(Set.of(), service.synchronizeQuizTopics(ACTIVITY).currentTopicIds());
        verify(activities).deleteAll(List.of(old));
        verify(questions, never()).findAllById_QuestionIdIn(any());
    }

    @Test
    void readsQuizTopicsFromQuestionsInsteadOfStaleActivityMappings() {
        when(questions.findAllById_QuestionIdIn(Set.of(Q1, Q2))).thenReturn(List.of(questionLink(Q2, B)));
        assertEquals(B, service.getActivityTopics(ACTIVITY).getFirst().getSkillTopicId());
        verify(activities, never()).findAllById_ActivityId(any());
        verify(access).requireReadAccess(CLASS_ID);
    }

    @Test
    void readsLessonQuestionAndAssignmentTopicsWithAuthorization() {
        when(lessons.findAllById_LessonId(LESSON))
                .thenReturn(List.of(new LessonSkillTopic(new LessonSkillTopicId(LESSON, A))));
        when(questions.findAllById_QuestionId(Q1)).thenReturn(List.of(questionLink(Q1, B)));
        assertEquals(A, service.getLessonTopics(LESSON).getFirst().getSkillTopicId());
        assertEquals(B, service.getQuestionTopics(Q1).getFirst().getSkillTopicId());
        when(scope.activityScope(ACTIVITY)).thenReturn(new ActivityScope(CLASS_ID, ActivityKind.ASSIGNMENT));
        assertTrue(service.getActivityTopics(ACTIVITY).isEmpty());
        verify(access, times(3)).requireReadAccess(CLASS_ID);
    }

    @Test
    void unknownResourceAndDeniedAccessPropagateWithoutWrites() {
        doThrow(new AppException(LEARNING_RESOURCE_NOT_FOUND)).when(scope).classIdForLesson(LESSON);
        assertError(LEARNING_RESOURCE_NOT_FOUND, () -> service.replaceLessonTopics(LESSON, List.of(A)));
        doThrow(new AppException(CLASS_ACCESS_DENIED)).when(access).lockForWrite(CLASS_ID);
        assertError(CLASS_ACCESS_DENIED, () -> service.replaceQuestionTopics(Q1, List.of(A)));
        verify(lessons, never()).saveAll(anyIterable());
        verify(questions, never()).saveAll(anyIterable());
    }

    @Test
    void rejectsUnpersistedQuestionAndDuplicateIds() {
        when(scope.questionIdsForQuizActivity(ACTIVITY)).thenReturn(Set.of(Q2));
        assertError(RESOURCE_STATE_CONFLICT, () -> service.replaceQuestionTopics(Q1, List.of(A)));
        assertError(INVALID_TOPIC_ASSOCIATION, () -> service.replaceLessonTopics(LESSON, List.of(A, A)));
        verify(questions, never()).saveAll(anyIterable());
    }

    @Test
    void changedHierarchyAbortsBeforeWrites() {
        when(scope.classIdForLesson(LESSON)).thenReturn(CLASS_ID, UUID.randomUUID());
        assertError(RESOURCE_CLASS_MISMATCH, () -> service.replaceLessonTopics(LESSON, List.of(A)));
        verify(lessons, never()).deleteAll(anyIterable());
    }

    private static QuestionSkillTopic questionLink(UUID questionId, UUID topicId) {
        return new QuestionSkillTopic(new QuestionSkillTopicId(questionId, topicId));
    }
}
