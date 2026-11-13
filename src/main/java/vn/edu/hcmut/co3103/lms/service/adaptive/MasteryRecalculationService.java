package vn.edu.hcmut.co3103.lms.service.adaptive;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.entity.LearnerMastery;
import vn.edu.hcmut.co3103.lms.entity.LearningSignal;
import vn.edu.hcmut.co3103.lms.enums.LearningSignalSourceType;
import vn.edu.hcmut.co3103.lms.enums.LearningSignalType;
import vn.edu.hcmut.co3103.lms.repository.adaptive.LearnerMasteryRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.LearningSignalRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Rebuilds derived state from canonical records within the source transaction.
 * Every entry point holds the real Class row lock before reading/writing derived
 * state. No end-user SecurityContext or active-membership policy is required.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class MasteryRecalculationService {
    private final ClassRepository classes;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final TopicQuizEvidenceReader quizEvidence;
    private final MasteryCalculator calculator;
    private final LearningSignalRepository signals;
    private final LearnerMasteryRepository masteries;

    /** Explicit internal repair/rebuild boundary; never an API or a scheduler. */
    @Transactional
    public void recalculatePair(UUID classId, UUID memberId, UUID topicId) {
        lockClass(classId);
        entityManager.flush();
        recalculateLocked(classId, memberId, topicId);
        entityManager.flush();
    }

    /** A single grading/retry affects this learner only, not every learner in the Quiz. */
    public void synchronizeAttempt(UUID attemptId) {
        AttemptScope scope = jdbc.query("""
                SELECT ch.class_id, cm.class_member_id, q.quiz_id FROM quiz_attempt qa
                JOIN class_member cm ON cm.class_member_id=qa.class_member_id
                JOIN quiz q ON q.quiz_id=qa.quiz_id JOIN activity a ON a.activity_id=q.activity_id
                JOIN lesson l ON l.lesson_id=a.lesson_id JOIN chapter ch ON ch.chapter_id=l.chapter_id
                WHERE qa.attempt_id=? AND cm.class_id=ch.class_id
                """, (row, index) -> new AttemptScope(row.getObject("class_id", UUID.class),
                row.getObject("class_member_id", UUID.class), row.getObject("quiz_id", UUID.class)), attemptId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Attempt scope not found"));
        lockClass(scope.classId());
        entityManager.flush();
        Set<UUID> topics = new HashSet<>(currentQuizTopics(scope.quizId(), scope.classId()));
        topics.addAll(jdbc.queryForList("""
                SELECT skill_topic_id FROM learning_signal WHERE class_member_id=?
                AND source_type='QUIZ_ATTEMPT' AND source_id=?
                """, UUID.class, scope.memberId(), attemptId));
        for (UUID topic : topics) recalculateLocked(scope.classId(), scope.memberId(), topic);
        entityManager.flush();
    }

    /** Called only after the entire grading/regrading/mapping operation is final. */
    public void synchronizeQuiz(UUID quizId, Set<UUID> changedTopicIds) {
        UUID classId = jdbc.queryForObject("""
                SELECT ch.class_id FROM quiz q JOIN activity a ON a.activity_id=q.activity_id
                JOIN lesson l ON l.lesson_id=a.lesson_id JOIN chapter ch ON ch.chapter_id=l.chapter_id
                WHERE q.quiz_id=?
                """, UUID.class, quizId);
        lockClass(classId);
        entityManager.flush();
        Set<UUID> topics = new HashSet<>(Objects.requireNonNull(changedTopicIds));
        topics.addAll(currentQuizTopics(quizId, classId));
        // Old associations remain discoverable from source signals even on a retry.
        topics.addAll(jdbc.queryForList("""
                SELECT DISTINCT s.skill_topic_id FROM learning_signal s
                JOIN quiz_attempt qa ON qa.attempt_id=s.source_id
                JOIN class_member cm ON cm.class_member_id=s.class_member_id
                WHERE s.source_type='QUIZ_ATTEMPT' AND qa.quiz_id=? AND cm.class_id=?
                """, UUID.class, quizId, classId));
        List<UUID> members = jdbc.queryForList("""
                SELECT DISTINCT cm.class_member_id FROM quiz_attempt qa
                JOIN class_member cm ON cm.class_member_id=qa.class_member_id
                WHERE qa.quiz_id=? AND cm.class_id=?
                """, UUID.class, quizId, classId);
        for (UUID topic : topics) {
            for (UUID member : members) recalculateLocked(classId, member, topic);
        }
        entityManager.flush();
    }

    public void synchronizeCompletion(UUID classId, UUID memberId) {
        lockClass(classId);
        entityManager.flush();
        synchronizeCompletionLocked(classId, memberId);
        entityManager.flush();
    }

    public void synchronizeLesson(UUID lessonId) {
        UUID classId = jdbc.queryForObject("""
                SELECT ch.class_id FROM lesson l JOIN chapter ch ON ch.chapter_id=l.chapter_id
                WHERE l.lesson_id=?
                """, UUID.class, lessonId);
        lockClass(classId);
        entityManager.flush();
        List<UUID> members = jdbc.queryForList("""
                SELECT DISTINCT cm.class_member_id FROM completion_mark mark
                JOIN class_member cm ON cm.class_member_id=mark.class_member_id
                WHERE mark.lesson_id=? AND cm.class_id=?
                """, UUID.class, lessonId, classId);
        for (UUID member : members) synchronizeCompletionLocked(classId, member);
        entityManager.flush();
    }

    private void recalculateLocked(UUID classId, UUID memberId, UUID topicId) {
        Integer matches = jdbc.queryForObject("""
                SELECT count(*) FROM class_member cm JOIN skill_topics st ON st.class_id=cm.class_id
                WHERE cm.class_member_id=? AND st.skill_topic_id=? AND cm.class_id=?
                """, Integer.class, memberId, topicId, classId);
        if (!Objects.equals(matches, 1)) throw new IllegalArgumentException("Learner/topic class scope mismatch");

        var evidence = quizEvidence.readAllGradedCandidates(classId, memberId, topicId);
        Map<UUID, LearningSignal> previous = signals
                .findByClassMemberIdAndSkillTopicIdAndSignalType(memberId, topicId, LearningSignalType.QUIZ_RESULT)
                .stream().collect(Collectors.toMap(LearningSignal::getSourceId, signal -> signal));
        for (var result : evidence) {
            LearningSignal signal = previous.remove(result.attemptId());
            if (signal == null) {
                signals.save(new LearningSignal(memberId, topicId, LearningSignalType.QUIZ_RESULT,
                        result.topicScore(), result.submittedAt(), LearningSignalSourceType.QUIZ_ATTEMPT,
                        result.attemptId()));
            } else {
                signal.update(result.topicScore(), result.submittedAt());
            }
        }
        signals.deleteAll(previous.values());

        var quizScores = evidence.stream().limit(3)
                .map(TopicQuizEvidenceReader.TopicQuizEvidence::topicScore).toList();
        // LMS-6 integration will supply canonical Assignment scores here. No Assignment
        // producer/reader exists yet; contextual signals NEVER enter either component.
        var result = calculator.calculateMastery(quizScores, List.of());
        var existing = masteries.findByClassMemberIdAndSkillTopicId(memberId, topicId);
        if (result.isEmpty()) {
            existing.ifPresent(masteries::delete);
        } else if (existing.isPresent()) {
            existing.orElseThrow().update(result.orElseThrow().score(), result.orElseThrow().level());
        } else {
            var value = result.orElseThrow();
            masteries.save(new LearnerMastery(memberId, topicId, value.score(), value.level()));
        }
    }

    private void synchronizeCompletionLocked(UUID classId, UUID memberId) {
        Integer matches = jdbc.queryForObject("SELECT count(*) FROM class_member WHERE class_member_id=? AND class_id=?",
                Integer.class, memberId, classId);
        if (!Objects.equals(matches, 1)) throw new IllegalArgumentException("Learner class scope mismatch");
        Map<CompletionSource, LearningSignal> previous = signals
                .findByClassMemberIdAndSignalType(memberId, LearningSignalType.LESSON_COMPLETION)
                .stream().collect(Collectors.toMap(s -> new CompletionSource(s.getSourceId(), s.getSkillTopicId()), s -> s));
        jdbc.query("""
                SELECT mark.mark_id, mark.marked_at, st.skill_topic_id FROM completion_mark mark
                JOIN lesson l ON l.lesson_id=mark.lesson_id JOIN chapter ch ON ch.chapter_id=l.chapter_id
                JOIN lesson_skill_topics mapping ON mapping.lesson_id=l.lesson_id
                JOIN skill_topics st ON st.skill_topic_id=mapping.skill_topic_id
                WHERE mark.class_member_id=? AND ch.class_id=? AND st.class_id=?
                """, row -> {
            UUID markId = row.getObject("mark_id", UUID.class);
            UUID topicId = row.getObject("skill_topic_id", UUID.class);
            OffsetDateTime event = row.getObject("marked_at", OffsetDateTime.class);
            LearningSignal existing = previous.remove(new CompletionSource(markId, topicId));
            if (existing == null) {
                signals.save(new LearningSignal(memberId, topicId, LearningSignalType.LESSON_COMPLETION,
                        BigDecimal.ONE, event, LearningSignalSourceType.COMPLETION_MARK, markId));
            } else existing.update(BigDecimal.ONE, event);
        }, memberId, classId, classId);
        signals.deleteAll(previous.values());
    }

    private void lockClass(UUID classId) {
        classes.findByIdForUpdate(Objects.requireNonNull(classId, "classId"))
                .orElseThrow(() -> new IllegalArgumentException("Class not found"));
    }

    private List<UUID> currentQuizTopics(UUID quizId, UUID classId) {
        return jdbc.queryForList("""
                SELECT DISTINCT st.skill_topic_id FROM question q
                JOIN question_skill_topics m ON m.question_id=q.question_id
                JOIN skill_topics st ON st.skill_topic_id=m.skill_topic_id
                WHERE q.quiz_id=? AND st.class_id=?
                """, UUID.class, quizId, classId);
    }

    private record AttemptScope(UUID classId, UUID memberId, UUID quizId) { }
    private record CompletionSource(UUID markId, UUID topicId) { }
}
