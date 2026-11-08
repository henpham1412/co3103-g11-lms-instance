package vn.edu.hcmut.co3103.lms.service.adaptive;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.hcmut.co3103.lms.entity.SkillTopic;
import vn.edu.hcmut.co3103.lms.repository.adaptive.SkillPrerequisiteRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.SkillTopicRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.ActivitySkillTopicRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.LessonSkillTopicRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.QuestionSkillTopicRepository;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.ClassAccess;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.LearningResourceScope;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercises real Spring transaction interception; does not simulate a PostgreSQL database. */
class AdaptiveServiceTransactionTest {

    @Test
    void prerequisiteReplacementCommitsOneTransaction() {
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        SkillPrerequisiteRepository prerequisites = mock(SkillPrerequisiteRepository.class);
        SkillPrerequisiteService service = prerequisiteProxy(prerequisites, transactions);
        service.replace(TOPIC_ID, List.of());
        assertEquals(1, transactions.commits);
        assertEquals(0, transactions.rollbacks);
    }

    @Test
    void persistenceFailureRollsBackInsteadOfCommitting() {
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        SkillPrerequisiteRepository prerequisites = mock(SkillPrerequisiteRepository.class);
        doThrow(new DataIntegrityViolationException("Write failed")).when(prerequisites).flush();
        SkillPrerequisiteService service = prerequisiteProxy(prerequisites, transactions);
        assertThrows(DataIntegrityViolationException.class, () -> service.replace(TOPIC_ID, List.of()));
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
    }

    @Test
    void questionHooksRequireAnExistingUpstreamTransaction() {
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        var target = mock(SkillTopicAssociationService.class);
        var service = proxy(target, transactions);
        assertThrows(IllegalTransactionStateException.class,
                () -> service.replaceQuestionTopics(UUID.randomUUID(), List.of()));
        assertThrows(IllegalTransactionStateException.class,
                () -> service.synchronizeQuizTopics(UUID.randomUUID()));
        verifyNoInteractions(target);
    }

    @Test
    void questionMappingAndQuizUnionJoinOneUpstreamTransaction() {
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        var activities = mock(ActivitySkillTopicRepository.class);
        var service = questionProxy(activities, transactions);
        new TransactionTemplate(transactions).executeWithoutResult(
                status -> service.replaceQuestionTopics(QUESTION_ID, List.of()));
        assertEquals(1, transactions.begins);
        assertEquals(1, transactions.commits);
        verify(activities).flush();
    }

    @Test
    void quizUnionFailureRollsBackTheUpstreamQuestionTransaction() {
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        var activities = mock(ActivitySkillTopicRepository.class);
        doThrow(new DataIntegrityViolationException("Quiz union failed")).when(activities).flush();
        var service = questionProxy(activities, transactions);
        assertThrows(DataIntegrityViolationException.class,
                () -> new TransactionTemplate(transactions).executeWithoutResult(
                        status -> service.replaceQuestionTopics(QUESTION_ID, List.of())));
        assertEquals(0, transactions.commits);
        assertEquals(1, transactions.rollbacks);
    }

    private static final UUID CLASS_ID = UUID.randomUUID();
    private static final UUID TOPIC_ID = UUID.randomUUID();

    private SkillPrerequisiteService prerequisiteProxy(SkillPrerequisiteRepository prerequisites,
                                                       RecordingTransactionManager transactions) {
        SkillTopicRepository topics = mock(SkillTopicRepository.class);
        when(topics.findClassIdBySkillTopicId(TOPIC_ID)).thenReturn(Optional.of(CLASS_ID));
        SkillTopic topic = new SkillTopic(TOPIC_ID, CLASS_ID, "Topic", null, null);
        when(topics.findById(TOPIC_ID)).thenReturn(Optional.of(topic));
        when(topics.findAllByClassId(CLASS_ID)).thenReturn(List.of(topic));
        ClassAccess access = mock(ClassAccess.class);
        doAnswer(call -> {
            assertTrue(transactions.state.active, "Class lock must be acquired inside a transaction");
            return null;
        }).when(access).lockForWrite(any());
        return proxy(new SkillPrerequisiteService(topics, prerequisites, access), transactions);
    }

    private static final UUID QUESTION_ID = UUID.randomUUID();
    private static final UUID ACTIVITY_ID = UUID.randomUUID();

    private SkillTopicAssociationService questionProxy(ActivitySkillTopicRepository activities,
                                                       RecordingTransactionManager transactions) {
        var scope = mock(LearningResourceScope.class);
        when(scope.questionScope(QUESTION_ID)).thenReturn(
                new LearningResourceScope.QuestionScope(CLASS_ID, ACTIVITY_ID));
        when(scope.activityScope(ACTIVITY_ID)).thenReturn(
                new LearningResourceScope.ActivityScope(CLASS_ID, LearningResourceScope.ActivityKind.QUIZ));
        when(scope.questionIdsForQuizActivity(ACTIVITY_ID)).thenReturn(Set.of(QUESTION_ID));
        return proxy(new SkillTopicAssociationService(mock(SkillTopicRepository.class),
                mock(LessonSkillTopicRepository.class), activities,
                mock(QuestionSkillTopicRepository.class), mock(ClassAccess.class), scope), transactions);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target, RecordingTransactionManager transactions) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactions);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }

    private static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private final State state = new State();
        private int begins;
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() { return state; }

        @Override
        protected boolean isExistingTransaction(Object transaction) { return state.active; }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            state.active = true;
            begins++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) { commits++; }

        @Override
        protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) { state.rollbackOnly = true; }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            state.active = false;
            state.rollbackOnly = false;
        }

        private static class State implements SmartTransactionObject {
            private boolean active;
            private boolean rollbackOnly;

            @Override
            public boolean isRollbackOnly() { return rollbackOnly; }

            @Override
            public void flush() { }
        }
    }
}
