package vn.edu.hcmut.co3103.lms.service.adaptive;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.hcmut.co3103.lms.dto.request.QuestionUpdateRequest;
import vn.edu.hcmut.co3103.lms.dto.request.SaveAnswerRequest;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.ClassAccessAdapter;
import vn.edu.hcmut.co3103.lms.service.assessment.QuizAttemptService;
import vn.edu.hcmut.co3103.lms.service.assessment.QuizAttemptSubmissionService;
import vn.edu.hcmut.co3103.lms.service.assessment.QuizQuestionService;
import vn.edu.hcmut.co3103.lms.service.progress.LessonCompletionService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "lms.quiz.expiry-scan-ms=3600000")
@Transactional
class MasteryPersistencePostgresTest {
    @Autowired JdbcTemplate db;
    @Autowired EntityManager em;
    @Autowired MasteryRecalculationService mastery;
    @Autowired QuizAttemptService attempts;
    @Autowired QuizAttemptSubmissionService submission;
    @Autowired QuizQuestionService questions;
    @Autowired SkillTopicAssociationService associations;
    @Autowired LessonCompletionService completion;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ClassRepository classes;

    UUID owner, student, course, chapter, lesson, member, topic, quiz, question, right, wrong;
    static final OffsetDateTime EVENT = OffsetDateTime.parse("2026-11-01T10:00:00Z");

    @BeforeEach
    void seed() {
        assertThat(db.queryForObject("SELECT version()", String.class)).contains("PostgreSQL");
        owner = user("TUTOR"); student = user("STUDENT"); course = course();
        chapter = UUID.randomUUID(); lesson = UUID.randomUUID(); member = UUID.randomUUID();
        db.update("INSERT INTO chapter(chapter_id,class_id,title,sequence_order) VALUES (?,?,'Chapter',0)", chapter, course);
        db.update("INSERT INTO lesson(lesson_id,chapter_id,title,sequence_order) VALUES (?,?,'Lesson',0)", lesson, chapter);
        db.update("INSERT INTO class_member(class_member_id,class_id,student_id,status) VALUES (?,?,?,'ACTIVE')", member, course, student);
        topic = topic(); quiz = UUID.randomUUID(); UUID activity = UUID.randomUUID();
        db.update("INSERT INTO activity(activity_id,lesson_id,title,activity_type,sequence_order) VALUES (?,?,'Quiz','QUIZ',0)", activity, lesson);
        db.update("INSERT INTO quiz(quiz_id,activity_id,time_limit_minutes,deadline,attempts_allowed) VALUES (?,?,30,?,100)", quiz, activity, OffsetDateTime.now().plusDays(1));
        question = UUID.randomUUID(); right = UUID.randomUUID(); wrong = UUID.randomUUID();
        db.update("INSERT INTO question(question_id,quiz_id,question_type,content,points_awarded,order_index) VALUES (?,?,'MCQ_SINGLE','Question',1,0)", question, quiz);
        db.update("INSERT INTO question_option(option_id,question_id,content,is_correct,order_index) VALUES (?,?,'Right',true,0),(?,?,'Wrong',false,1)", right, question, wrong, question);
        db.update("INSERT INTO question_skill_topics(question_id,skill_topic_id) VALUES (?,?)", question, topic);
    }

    @AfterEach
    void cleanupCommittedFixtures() {
        // Transactional tests roll back; only concurrency/recovery tests commit fixtures.
        if (TransactionSynchronizationManager.isActualTransactionActive()) return;
        db.update("DELETE FROM attempt_answer_option WHERE answer_id IN (SELECT answer_id FROM attempt_answer WHERE question_id=?)", question);
        db.update("DELETE FROM attempt_answer WHERE question_id=?", question);
        db.update("DELETE FROM quiz_attempt WHERE quiz_id=?", quiz);
        db.update("DELETE FROM question_skill_topics WHERE question_id=?", question);
        db.update("DELETE FROM activity_skill_topics WHERE activity_id=(SELECT activity_id FROM quiz WHERE quiz_id=?)", quiz);
        db.update("DELETE FROM question_option WHERE question_id=?", question);
        db.update("DELETE FROM question WHERE question_id=?", question);
        db.update("DELETE FROM quiz WHERE quiz_id=?", quiz);
        db.update("DELETE FROM completion_mark WHERE class_member_id=?", member);
        db.update("DELETE FROM lesson_skill_topics WHERE lesson_id=?", lesson);
        db.update("DELETE FROM skill_topics WHERE class_id=?", course);
        db.update("DELETE FROM activity WHERE lesson_id=?", lesson);
        db.update("DELETE FROM lesson WHERE lesson_id=?", lesson);
        db.update("DELETE FROM chapter WHERE chapter_id=?", chapter);
        db.update("DELETE FROM class_member WHERE class_member_id=?", member);
        db.update("DELETE FROM class WHERE class_id=?", course);
        db.update("DELETE FROM lms_user_access WHERE user_id IN (?,?)", owner, student);
    }

    @Test
    void pendingSubmissionCreatesNoEvidenceUntilGradingAndRetriesKeepStableIdentity() {
        UUID attempt = start(wrong);
        submission.submit(attempt, student); em.flush();
        assertThat(db.queryForObject("SELECT submitted_at FROM quiz_attempt WHERE attempt_id=?", OffsetDateTime.class, attempt)).isNotNull();
        assertThat(db.queryForObject("SELECT score FROM quiz_attempt WHERE attempt_id=?", BigDecimal.class, attempt)).isNull();
        assertThat(count("learning_signal")).isZero(); assertThat(count("learner_mastery")).isZero();
        submission.gradeIfPending(attempt); em.flush();
        assertThat(score()).isEqualByComparingTo("0"); assertThat(level()).isEqualTo("WEAK");
        var originalSignal = db.queryForMap("SELECT * FROM learning_signal WHERE class_member_id=?", member);
        var originalMastery = db.queryForMap("SELECT * FROM learner_mastery WHERE class_member_id=?", member);
        submission.gradeIfPending(attempt); mastery.recalculatePair(course, member, topic); em.flush();
        assertThat(db.queryForMap("SELECT * FROM learning_signal WHERE class_member_id=?", member)).isEqualTo(originalSignal);
        assertThat(db.queryForMap("SELECT * FROM learner_mastery WHERE class_member_id=?", member)).isEqualTo(originalMastery);
    }

    @Test
    void allSignalsAreStoredButOnlyLatestThreeCountAndFourthIsPromotedAtFullPrecision() {
        evidence(0, false); evidence(1, true); evidence(2, true); UUID newest = evidence(3, true);
        mastery.recalculatePair(course, member, topic);
        assertThat(count("learning_signal")).isEqualTo(4); assertThat(score()).isEqualByComparingTo("100");
        db.update("UPDATE quiz_attempt SET score=NULL,graded_at=NULL WHERE attempt_id=?", newest);
        mastery.recalculatePair(course, member, topic);
        assertThat(count("learning_signal")).isEqualTo(3);
        assertThat(score()).isEqualByComparingTo("66.66666666666666666666666666666667");
        assertThat(level()).isEqualTo("DEVELOPING");
    }

    @Test
    void automaticExpiryGradingCreatesEvidenceWithoutAnEndUserSecurityContext() {
        UUID attempt = start(right); em.flush();
        db.update("UPDATE quiz_attempt SET started_at=? WHERE attempt_id=?", OffsetDateTime.now().minusMinutes(31), attempt);
        em.clear();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        attempts.expireIfDue(attempt); em.flush();
        assertThat(db.queryForObject("SELECT auto_submitted FROM quiz_attempt WHERE attempt_id=?", Boolean.class, attempt)).isTrue();
        assertThat(count("learning_signal")).isEqualTo(1);
        assertThat(score()).isEqualByComparingTo("100");
    }

    @Test
    void gradingAnOlderPendingSubmissionDoesNotPromoteItIntoTheLatestThree() {
        UUID old = evidence(0, false); evidence(1, true); evidence(2, true); evidence(3, true);
        db.update("UPDATE quiz_attempt SET score=NULL,graded_at=NULL WHERE attempt_id=?", old);
        mastery.recalculatePair(course, member, topic);
        assertThat(count("learning_signal")).isEqualTo(3);
        submission.gradeIfPending(old); em.flush();
        assertThat(count("learning_signal")).isEqualTo(4);
        assertThat(score()).isEqualByComparingTo("100");
        assertThat(db.queryForObject("SELECT recorded_at FROM learning_signal WHERE source_id=?", OffsetDateTime.class, old)).isEqualTo(EVENT);
    }

    @Test
    void persistenceDoesNotRoundADevelopingScoreUpToTheSufficientBoundary() {
        db.update("UPDATE question SET points_awarded=7999 WHERE question_id=?", question);
        UUID small = mappedQuestion(1, 1); mappedQuestion(2, 2000);
        for (int i = 0; i < 3; i++) {
            UUID id = UUID.randomUUID();
            db.update("INSERT INTO quiz_attempt(attempt_id,quiz_id,class_member_id,attempt_number,status,submitted_at,score,graded_at) VALUES (?,?,?,?,'SUBMITTED',?,?,?)",
                    id, quiz, member, i + 1, EVENT.plusMinutes(i), new BigDecimal(i == 0 ? "79.99" : "80.00"), EVENT.plusMinutes(i + 1));
            db.update("INSERT INTO attempt_answer(attempt_id,question_id,is_correct,points_earned) VALUES (?,?,true,7999)", id, question);
            if (i > 0) db.update("INSERT INTO attempt_answer(attempt_id,question_id,is_correct,points_earned) VALUES (?,?,true,1)", id, small);
        }
        mastery.recalculatePair(course, member, topic);
        assertThat(score()).isGreaterThan(new BigDecimal("79.99")).isLessThan(new BigDecimal("80"));
        assertThat(score().precision()).isGreaterThan(10);
        assertThat(level()).isEqualTo("DEVELOPING");
    }

    @Test
    void regradingUpdatesOneSignalAndMasteryWithoutChangingSourceIdentityOrEventTime() {
        UUID attempt = start(wrong); submission.submit(attempt, student); submission.gradeIfPending(attempt); em.flush();
        var original = db.queryForMap("SELECT * FROM learning_signal WHERE class_member_id=?", member);
        var changed = flippedKey();
        questions.updateQuestion(question, owner, changed); em.flush();
        var signal = db.queryForMap("SELECT * FROM learning_signal WHERE class_member_id=?", member);
        assertThat(signal.get("learning_signal_id")).isEqualTo(original.get("learning_signal_id"));
        assertThat(signal.get("recorded_at")).isEqualTo(original.get("recorded_at"));
        assertThat((BigDecimal) signal.get("signal_value")).isEqualByComparingTo("100");
        assertThat(score()).isEqualByComparingTo("100"); assertThat(level()).isEqualTo("SUFFICIENT");
    }

    @Test
    void mappingAdditionRemovalAndMultiTopicQuestionRebuildOldAndNewPairs() {
        mapping(List.of());
        UUID attempt = start(right); submission.submit(attempt, student); submission.gradeIfPending(attempt);
        assertThat(count("learner_mastery")).isZero();
        UUID other = topic(); mapping(List.of(topic, other));
        assertThat(count("learning_signal")).isEqualTo(2); assertThat(count("learner_mastery")).isEqualTo(2);
        mapping(List.of(other));
        assertThat(count("learning_signal")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT skill_topic_id FROM learner_mastery WHERE class_member_id=?", UUID.class, member)).isEqualTo(other);
        mapping(List.of());
        assertThat(count("learning_signal")).isZero(); assertThat(count("learner_mastery")).isZero();
    }

    @Test
    void simultaneousAnswerKeyAndTopicChangeUsesFinalRegradedState() {
        UUID attempt = start(wrong); submission.submit(attempt, student); submission.gradeIfPending(attempt);
        UUID other = topic(); var request = flippedKey(); request.setSkillTopicIds(List.of(other));
        questions.updateQuestion(question, owner, request); em.flush();
        assertThat(count("learning_signal")).isEqualTo(1); assertThat(count("learner_mastery")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT skill_topic_id FROM learner_mastery WHERE class_member_id=?", UUID.class, member)).isEqualTo(other);
        assertThat(db.queryForObject("SELECT mastery_score FROM learner_mastery WHERE class_member_id=?", BigDecimal.class, member)).isEqualByComparingTo("100");
    }

    @Test
    void zeroDenominatorDeletesStaleMasteryAndSignalInsteadOfFabricatingWeak() {
        UUID attempt = start(right); submission.submit(attempt, student); submission.gradeIfPending(attempt);
        var request = new QuestionUpdateRequest(); request.setPointsAwarded(BigDecimal.ZERO);
        questions.updateQuestion(question, owner, request); em.flush();
        assertThat(count("learning_signal")).isZero(); assertThat(count("learner_mastery")).isZero();
    }

    @Test
    void completionAndLessonMappingChangesSynchronizeContextOnly() {
        lessonMapping(List.of(topic));
        completion.markComplete(course, lesson, student); em.flush();
        assertThat(count("learning_signal")).isEqualTo(1); assertThat(count("learner_mastery")).isZero();
        UUID signal = db.queryForObject("SELECT learning_signal_id FROM learning_signal WHERE class_member_id=?", UUID.class, member);
        completion.markComplete(course, lesson, student); em.flush();
        assertThat(db.queryForObject("SELECT learning_signal_id FROM learning_signal WHERE class_member_id=?", UUID.class, member)).isEqualTo(signal);
        UUID other = topic(); lessonMapping(List.of(other));
        assertThat(count("learning_signal")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT skill_topic_id FROM learning_signal WHERE class_member_id=?", UUID.class, member)).isEqualTo(other);
        assertThat(count("learner_mastery")).isZero();
        completion.markIncomplete(course, lesson, student); em.flush();
        assertThat(count("learning_signal")).isZero(); assertThat(count("learner_mastery")).isZero();
    }

    @Test
    void completionCannotIncreaseExistingAssessmentMastery() {
        UUID attempt = start(wrong); submission.submit(attempt, student); submission.gradeIfPending(attempt);
        lessonMapping(List.of(topic)); completion.markComplete(course, lesson, student); em.flush();
        assertThat(count("learning_signal")).isEqualTo(2);
        assertThat(score()).isEqualByComparingTo("0"); assertThat(level()).isEqualTo("WEAK");
    }

    @Test
    void databaseRejectsDuplicateLearnerTopicMastery() {
        evidence(0, true); mastery.recalculatePair(course, member, topic);
        assertThatThrownBy(() -> db.update("INSERT INTO learner_mastery(class_member_id,skill_topic_id,mastery_level,mastery_score) VALUES (?,?,'SUFFICIENT',100)", member, topic))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_learner_mastery_member_topic");
    }

    @Test
    void databaseRejectsDuplicateSignalSourceIdentity() {
        UUID attempt = evidence(0, true); mastery.recalculatePair(course, member, topic);
        assertThatThrownBy(() -> db.update("INSERT INTO learning_signal(class_member_id,skill_topic_id,signal_type,signal_value,recorded_at,source_type,source_id) VALUES (?,?,'QUIZ_RESULT',100,?,'QUIZ_ATTEMPT',?)", member, topic, EVENT, attempt))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_learning_signal_source");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void gradingAndDerivedStateRollBackTogetherAndPendingGradeCanRetry() {
        UUID attempt = start(right); submission.submit(attempt, student);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            submission.gradeIfPending(attempt);
            assertThat(score()).isEqualByComparingTo("100");
            assertThat(count("learning_signal")).isEqualTo(1);
            throw new IllegalStateException("Injected failure after synchronization");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT score FROM quiz_attempt WHERE attempt_id=?", BigDecimal.class, attempt)).isNull();
        assertThat(count("learning_signal")).isZero(); assertThat(count("learner_mastery")).isZero();
        submission.gradeIfPending(attempt);
        assertThat(count("learning_signal")).isEqualTo(1); assertThat(score()).isEqualByComparingTo("100");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRegradingAndMappingRespectClassLockAndCannotLeaveOldTopicMastery() throws Exception {
        UUID attempt = start(wrong); submission.submit(attempt, student); submission.gradeIfPending(attempt);
        UUID other = topic(); CountDownLatch gradedUnderClassLock = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                classes.findByIdForUpdate(course).orElseThrow();
                questions.updateQuestion(question, owner, flippedKey());
                gradedUnderClassLock.countDown();
                await(secondStarted);
            }));
            var second = pool.submit(() -> {
                await(gradedUnderClassLock); secondStarted.countDown();
                mapping(List.of(other));
            });
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        }
        assertThat(count("learning_signal")).isEqualTo(1); assertThat(count("learner_mastery")).isEqualTo(1);
        assertThat(db.queryForObject("SELECT skill_topic_id FROM learner_mastery WHERE class_member_id=?", UUID.class, member)).isEqualTo(other);
        assertThat(db.queryForObject("SELECT mastery_score FROM learner_mastery WHERE class_member_id=?", BigDecimal.class, member)).isEqualByComparingTo("100");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentInternalRetriesAreIdempotentWithoutAnEndUserSecurityContext() throws Exception {
        evidence(0, true);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> mastery.recalculatePair(course, member, topic));
            var second = pool.submit(() -> mastery.recalculatePair(course, member, topic));
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
        }
        assertThat(count("learning_signal")).isEqualTo(1); assertThat(count("learner_mastery")).isEqualTo(1);
    }

    @Test
    void crossClassPairIsRejectedWithoutCreatingAnyDerivedState() {
        UUID foreignClass = course(); UUID foreignTopic = UUID.randomUUID();
        db.update("INSERT INTO skill_topics(skill_topic_id,class_id,name,difficulty_level) VALUES (?,?,'Foreign',1)", foreignTopic, foreignClass);
        assertThatThrownBy(() -> mastery.recalculatePair(course, member, foreignTopic)).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("learning_signal")).isZero(); assertThat(count("learner_mastery")).isZero();
    }

    private UUID start(UUID option) {
        UUID attempt = attempts.startOrResume(quiz, student).body().attemptId();
        attempts.saveAnswer(attempt, question, student, new SaveAnswerRequest(List.of(option)));
        return attempt;
    }

    private void mapping(List<UUID> topics) {
        var request = new QuestionUpdateRequest(); request.setSkillTopicIds(topics);
        questions.updateQuestion(question, owner, request);
    }

    private void lessonMapping(List<UUID> topics) {
        ClassAccessAdapter.withActor(owner, () -> associations.replaceLessonTopics(lesson, topics));
    }

    private QuestionUpdateRequest flippedKey() {
        var request = new QuestionUpdateRequest();
        request.setOptions(List.of(new QuestionUpdateRequest.Option(right, "Right", false, 0),
                new QuestionUpdateRequest.Option(wrong, "Wrong", true, 1)));
        return request;
    }

    private UUID evidence(int minute, boolean correct) {
        UUID id = UUID.randomUUID();
        int number = db.queryForObject("SELECT COALESCE(MAX(attempt_number),0)+1 FROM quiz_attempt WHERE quiz_id=?", Integer.class, quiz);
        db.update("INSERT INTO quiz_attempt(attempt_id,quiz_id,class_member_id,attempt_number,status,submitted_at,score,graded_at) VALUES (?,?,?,?,'SUBMITTED',?,?,?)",
                id, quiz, member, number, EVENT.plusMinutes(minute), correct ? 100 : 0, EVENT.plusMinutes(minute + 1));
        if (correct) db.update("INSERT INTO attempt_answer(attempt_id,question_id,is_correct,points_earned) VALUES (?,?,true,1)", id, question);
        return id;
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO lms_user_access(user_id,full_name,email,lms_role) VALUES (?,'Mastery test',?,?)", id, id + "@example.test", role);
        return id;
    }

    private UUID mappedQuestion(int order, int points) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO question(question_id,quiz_id,question_type,content,points_awarded,order_index) VALUES (?,?,'MCQ_SINGLE','Weighted',?,?)", id, quiz, points, order);
        db.update("INSERT INTO question_option(question_id,content,is_correct,order_index) VALUES (?,'Right',true,0),(?,'Wrong',false,1)", id, id);
        db.update("INSERT INTO question_skill_topics(question_id,skill_topic_id) VALUES (?,?)", id, topic);
        return id;
    }

    private UUID course() {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO class(class_id,tutor_id,class_code,title,enrollment_policy) VALUES (?,?,?,'Course','OPEN')", id, owner, id.toString().substring(0, 30));
        return id;
    }

    private UUID topic() {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO skill_topics(skill_topic_id,class_id,name,difficulty_level) VALUES (?,?,?,1)", id, course, id.toString());
        return id;
    }

    private int count(String table) {
        return db.queryForObject("SELECT count(*) FROM " + table + " WHERE class_member_id=?", Integer.class, member);
    }

    private BigDecimal score() {
        return db.queryForObject("SELECT mastery_score FROM learner_mastery WHERE class_member_id=? AND skill_topic_id=?", BigDecimal.class, member, topic);
    }

    private String level() {
        return db.queryForObject("SELECT mastery_level FROM learner_mastery WHERE class_member_id=? AND skill_topic_id=?", String.class, member, topic);
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
