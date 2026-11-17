package vn.edu.hcmut.co3103.lms.service.adaptive;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real PostgreSQL, canonical Flyway schema, per-test rollback; no H2 or repository mocks.
 * Exercises the coordinator-authorized D-08 historical-evidence working policy,
 * without implying formal whole-group approval or current access permission.
 */
class TopicQuizEvidenceReaderPostgresTest {

    private static final OffsetDateTime EVENT = OffsetDateTime.parse("2026-11-12T10:00:00Z");
    private static DriverManagerDataSource source;
    private JdbcTemplate db;
    private NamedParameterJdbcTemplate named;
    private DataSourceTransactionManager transactions;
    private TransactionStatus transaction;
    private TopicQuizEvidenceReader reader;
    private UUID classId;
    private UUID lessonId;
    private UUID memberId;
    private UUID topicId;

    @BeforeAll
    static void database() throws Exception {
        source = new DriverManagerDataSource(
                System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/lms_instance_dev"),
                System.getenv().getOrDefault("DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("DB_PASSWORD", ""));
        try (var connection = source.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void seed() {
        transactions = new DataSourceTransactionManager(source);
        transaction = transactions.getTransaction(new DefaultTransactionDefinition());
        db = new JdbcTemplate(source);
        named = new NamedParameterJdbcTemplate(db);
        reader = new TopicQuizEvidenceReader(named, new MasteryCalculator());
        classId = course();
        UUID chapterId = UUID.randomUUID();
        db.update("INSERT INTO chapter(chapter_id,class_id,title,sequence_order) VALUES (?,?,'Chapter',0)",
                chapterId, classId);
        lessonId = UUID.randomUUID();
        db.update("INSERT INTO lesson(lesson_id,chapter_id,title,sequence_order) VALUES (?,?,'Lesson',0)",
                lessonId, chapterId);
        memberId = member(classId);
        topicId = topic(classId);
    }

    @AfterEach
    void rollback() {
        if (transaction != null) transactions.rollback(transaction);
    }

    @Test
    void unansweredQuestionsRemainInDenominatorAndAllUnansweredIsRealZeroEvidence() {
        UUID quiz = quiz();
        UUID answered = question(quiz, 0, "2", topicId);
        question(quiz, 1, "3", topicId);
        UUID first = attempt(quiz, memberId, EVENT);
        answer(first, answered, "2");
        UUID unanswered = attempt(quiz, memberId, EVENT.plusMinutes(1));

        var evidence = read();
        assertThat(evidence).extracting(TopicQuizEvidenceReader.TopicQuizEvidence::attemptId)
                .containsExactly(unanswered, first);
        assertThat(evidence).extracting(TopicQuizEvidenceReader.TopicQuizEvidence::topicScore)
                .containsExactly(decimal("0.00"), decimal("40.00"));
        assertThat(evidence).allSatisfy(result -> {
            assertThat(result.classMemberId()).isEqualTo(memberId);
            assertThat(result.skillTopicId()).isEqualTo(topicId);
        });
    }

    @Test
    void storedDeductionsAndUnequalPointsAreUsedWithoutRegradingOrUsingFinalQuizScore() {
        UUID quiz = quiz();
        UUID correct = question(quiz, 0, "6", topicId);
        UUID wrong = question(quiz, 1, "3", topicId);
        question(quiz, 2, "1", topicId);
        UUID attempt = attempt(quiz, memberId, EVENT);
        answer(attempt, correct, "6");
        answer(attempt, wrong, "-2");
        // No options exist: a reader must use stored grading, not re-evaluate answers.
        assertThat(read().getFirst().topicScore()).isEqualByComparingTo("40.00");
        assertThat(db.queryForObject("SELECT points_earned FROM attempt_answer WHERE attempt_id=? AND question_id=?",
                BigDecimal.class, attempt, wrong)).isEqualByComparingTo("-2");
    }

    @Test
    void identicalPossiblePointsAreCountedForEveryDistinctQuestion() {
        UUID quiz = quiz();
        UUID first = question(quiz, 0, "5", topicId);
        question(quiz, 1, "5", topicId);
        UUID attempt = attempt(quiz, memberId, EVENT);
        answer(attempt, first, "5");
        assertThat(read().getFirst().topicScore()).isEqualByComparingTo("50.00");
    }

    @Test
    void oneQuestionCanProvideOneIndependentResultForEachMappedTopic() {
        UUID otherTopic = topic(classId);
        UUID quiz = quiz();
        UUID q = question(quiz, 0, "3", topicId);
        map(q, otherTopic);
        UUID attempt = attempt(quiz, memberId, EVENT);
        answer(attempt, q, "2");
        assertThat(read()).hasSize(1);
        var other = reader.readLatestGradedCandidates(classId, memberId, otherTopic);
        assertThat(other).hasSize(1);
        assertThat(other.getFirst().attemptId()).isEqualTo(attempt);
        assertThat(other.getFirst().topicScore()).isEqualByComparingTo("66.67");
        assertThat(read().getFirst().topicScore()).isEqualByComparingTo("66.67");
    }

    @Test
    void latestThreeAreSelectedAcrossQuizzesAfterZeroDenominatorAndMappingFilters() {
        UUID oldest = scoredQuiz(EVENT, "10", "1");
        UUID second = scoredQuiz(EVENT.plusMinutes(1), "10", "2");
        UUID third = scoredQuiz(EVENT.plusMinutes(2), "10", "3");
        UUID fourth = scoredQuiz(EVENT.plusMinutes(3), "10", "4");
        UUID zeroQuiz = quiz();
        question(zeroQuiz, 0, "0", topicId);
        attempt(zeroQuiz, memberId, EVENT.plusMinutes(4));
        UUID unmappedQuiz = quiz();
        question(unmappedQuiz, 0, "10", null);
        attempt(unmappedQuiz, memberId, EVENT.plusMinutes(5));

        assertThat(read()).extracting(TopicQuizEvidenceReader.TopicQuizEvidence::attemptId)
                .containsExactly(fourth, third, second).doesNotContain(oldest);
    }

    @Test
    void missingMappingAndZeroDenominatorProvideNoEvidence() {
        UUID quiz = quiz();
        UUID q = question(quiz, 0, "5", null);
        UUID attempt = attempt(quiz, memberId, EVENT);
        answer(attempt, q, "5");
        assertThat(read()).isEmpty();
        map(q, topicId);
        db.update("UPDATE question SET points_awarded=0 WHERE question_id=?", q);
        assertThat(read()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "NO_SCORE", "NO_GRADE_TIME", "NO_SUBMIT_TIME"})
    void excludesAttemptsThatFailAnyCanonicalGradingGateBeforeLimiting(String missing) {
        UUID eligible = scoredQuiz(EVENT, "10", "8");
        for (int i = 1; i <= 4; i++) {
            UUID candidate = scoredQuiz(EVENT.plusMinutes(i), "10", "9");
            switch (missing) {
                case "IN_PROGRESS" -> db.update("UPDATE quiz_attempt SET status='IN_PROGRESS' WHERE attempt_id=?", candidate);
                case "NO_SCORE" -> db.update("UPDATE quiz_attempt SET score=NULL WHERE attempt_id=?", candidate);
                case "NO_GRADE_TIME" -> db.update("UPDATE quiz_attempt SET graded_at=NULL WHERE attempt_id=?", candidate);
                case "NO_SUBMIT_TIME" -> db.update("UPDATE quiz_attempt SET submitted_at=NULL WHERE attempt_id=?", candidate);
                default -> throw new AssertionError(missing);
            }
        }
        assertThat(read()).extracting(TopicQuizEvidenceReader.TopicQuizEvidence::attemptId).containsExactly(eligible);
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PROGRESS", "NO_SCORE", "NO_GRADE_TIME", "NO_SUBMIT_TIME"})
    void newestIneligibleAttemptCannotDisplaceTheThirdEligibleResult(String missing) {
        scoredQuiz(EVENT, "10", "1");
        UUID second = scoredQuiz(EVENT.plusMinutes(1), "10", "2");
        UUID third = scoredQuiz(EVENT.plusMinutes(2), "10", "3");
        UUID fourth = scoredQuiz(EVENT.plusMinutes(3), "10", "4");
        UUID newest = scoredQuiz(EVENT.plusMinutes(4), "10", "5");
        switch (missing) {
            case "IN_PROGRESS" -> db.update("UPDATE quiz_attempt SET status='IN_PROGRESS' WHERE attempt_id=?", newest);
            case "NO_SCORE" -> db.update("UPDATE quiz_attempt SET score=NULL WHERE attempt_id=?", newest);
            case "NO_GRADE_TIME" -> db.update("UPDATE quiz_attempt SET graded_at=NULL WHERE attempt_id=?", newest);
            case "NO_SUBMIT_TIME" -> db.update("UPDATE quiz_attempt SET submitted_at=NULL WHERE attempt_id=?", newest);
            default -> throw new AssertionError(missing);
        }
        assertThat(read()).extracting(TopicQuizEvidenceReader.TopicQuizEvidence::attemptId)
                .containsExactly(fourth, third, second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVITY_HIDDEN", "LESSON_HIDDEN", "CHAPTER_HIDDEN", "CLASS_ARCHIVED",
            "MEMBER_INACTIVE", "MEMBER_PENDING", "MEMBER_REJECTED", "LOCAL_ACCESS_SUSPENDED"})
    void laterLifecycleChangesDoNotInvalidateValidHistoricalEvidence(String change) {
        UUID original = scoredQuiz(EVENT, "10", "8");
        var before = read();
        assertThat(before).hasSize(1);
        switch (change) {
            case "ACTIVITY_HIDDEN" -> db.update("UPDATE activity SET status='HIDDEN' WHERE activity_id=(SELECT q.activity_id FROM quiz q JOIN quiz_attempt a ON a.quiz_id=q.quiz_id WHERE a.attempt_id=?)", original);
            case "LESSON_HIDDEN" -> db.update("UPDATE lesson SET status='HIDDEN' WHERE lesson_id=?", lessonId);
            case "CHAPTER_HIDDEN" -> db.update("UPDATE chapter SET status='HIDDEN' WHERE chapter_id=(SELECT chapter_id FROM lesson WHERE lesson_id=?)", lessonId);
            case "CLASS_ARCHIVED" -> db.update("UPDATE class SET status='ARCHIVED' WHERE class_id=?", classId);
            case "MEMBER_INACTIVE" -> db.update("UPDATE class_member SET status='INACTIVE' WHERE class_member_id=?", memberId);
            case "MEMBER_PENDING" -> db.update("UPDATE class_member SET status='PENDING' WHERE class_member_id=?", memberId);
            case "MEMBER_REJECTED" -> db.update("UPDATE class_member SET status='REJECTED' WHERE class_member_id=?", memberId);
            case "LOCAL_ACCESS_SUSPENDED" -> db.update("UPDATE lms_user_access SET access_status='SUSPENDED' WHERE user_id=(SELECT student_id FROM class_member WHERE class_member_id=?)", memberId);
            default -> throw new AssertionError(change);
        }
        assertThat(read()).containsExactlyElementsOf(before);
        assertThat(read().getFirst().topicScore()).isEqualByComparingTo("80.00");
    }

    @Test
    void physicallyDeletedAttemptDisappearsAndPromotesTheFourthLatestResult() {
        UUID oldest = scoredQuiz(EVENT, "10", "1");
        UUID second = scoredQuiz(EVENT.plusMinutes(1), "10", "2");
        UUID third = scoredQuiz(EVENT.plusMinutes(2), "10", "3");
        UUID newest = scoredQuiz(EVENT.plusMinutes(3), "10", "4");
        assertThat(read()).extracting(TopicQuizEvidenceReader.TopicQuizEvidence::attemptId)
                .containsExactly(newest, third, second);
        // Storage-level deletion, not an invented cancellation/deletion API.
        // The canonical FK requires removing dependent answers before the attempt.
        db.update("DELETE FROM attempt_answer WHERE attempt_id=?", newest);
        db.update("DELETE FROM quiz_attempt WHERE attempt_id=?", newest);
        assertThat(read()).extracting(TopicQuizEvidenceReader.TopicQuizEvidence::attemptId)
                .containsExactly(third, second, oldest);
    }

    @Test
    void equalSubmissionTimesUsePostgresUuidDescendingOrder() {
        UUID quiz = quiz();
        question(quiz, 0, "1", topicId);
        var ids = List.of(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"),
                UUID.fromString("80000000-0000-0000-0000-000000000000"),
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        for (UUID id : ids) attempt(id, quiz, memberId, EVENT);
        // Cross the signed Java UUID boundary: let PostgreSQL order, never Java UUID.compareTo.
        assertThat(read()).extracting(TopicQuizEvidenceReader.TopicQuizEvidence::attemptId)
                .containsExactly(ids.get(3), ids.get(2), ids.get(1));
    }

    @Test
    void regradingChangesEvidenceValueWithoutPromotingAnOldSubmission() {
        UUID quiz = quiz();
        UUID q = question(quiz, 0, "10", topicId);
        UUID old = attempt(quiz, memberId, EVENT);
        answer(old, q, "2");
        UUID recent = attempt(quiz, memberId, EVENT.plusMinutes(1));
        answer(recent, q, "4");
        db.update("UPDATE attempt_answer SET points_earned=9 WHERE attempt_id=?", old);
        db.update("UPDATE quiz_attempt SET score=90,graded_at=? WHERE attempt_id=?", EVENT.plusDays(1), old);
        var evidence = read();
        assertThat(evidence).extracting(TopicQuizEvidenceReader.TopicQuizEvidence::attemptId)
                .containsExactly(recent, old);
        assertThat(evidence.get(1).topicScore()).isEqualByComparingTo("90.00");
        assertThat(evidence.get(1).submittedAt()).isEqualTo(EVENT);
    }

    @Test
    void mappingChangesAreReadFreshAndRemovalPromotesFourthLatestEvidence() {
        UUID first = scoredQuiz(EVENT, "10", "1");
        scoredQuiz(EVENT.plusMinutes(1), "10", "2");
        scoredQuiz(EVENT.plusMinutes(2), "10", "3");
        UUID newest = scoredQuiz(EVENT.plusMinutes(3), "10", "4");
        UUID newestQuestion = db.queryForObject("SELECT q.question_id FROM question q JOIN quiz_attempt a ON a.quiz_id=q.quiz_id WHERE a.attempt_id=?",
                UUID.class, newest);
        db.update("DELETE FROM question_skill_topics WHERE question_id=? AND skill_topic_id=?", newestQuestion, topicId);
        assertThat(read()).extracting(TopicQuizEvidenceReader.TopicQuizEvidence::attemptId)
                .doesNotContain(newest).contains(first);
        map(newestQuestion, topicId);
        assertThat(read()).extracting(TopicQuizEvidenceReader.TopicQuizEvidence::attemptId)
                .contains(newest).doesNotContain(first);
    }

    @Test
    void wrongMemberAndRequestedClassCannotLeakEvidence() {
        UUID quiz = quiz();
        UUID q = question(quiz, 0, "10", topicId);
        UUID own = attempt(quiz, memberId, EVENT);
        answer(own, q, "2");
        UUID otherMember = member(classId);
        UUID otherAttempt = attempt(quiz, otherMember, EVENT.plusMinutes(1));
        answer(otherAttempt, q, "9");
        assertThat(read()).extracting(TopicQuizEvidenceReader.TopicQuizEvidence::attemptId).containsExactly(own);
        assertThat(reader.readLatestGradedCandidates(classId, otherMember, topicId))
                .extracting(TopicQuizEvidenceReader.TopicQuizEvidence::attemptId).containsExactly(otherAttempt);
        assertThat(reader.readLatestGradedCandidates(UUID.randomUUID(), memberId, topicId)).isEmpty();
        assertThat(reader.readLatestGradedCandidates(classId, UUID.randomUUID(), topicId)).isEmpty();
    }

    @Test
    void rejectsCrossClassTopicMembershipAndQuizHierarchyEvenWhenSeparateForeignKeysAreValid() {
        UUID foreignClass = course();
        UUID foreignTopic = topic(foreignClass);
        UUID quiz = quiz();
        UUID q = question(quiz, 0, "10", foreignTopic); // Deliberately bypass service validation.
        UUID attempt = attempt(quiz, memberId, EVENT);
        answer(attempt, q, "8");
        assertThat(reader.readLatestGradedCandidates(classId, memberId, foreignTopic)).isEmpty();
        db.update("DELETE FROM question_skill_topics WHERE question_id=?", q);
        map(q, topicId);
        UUID foreignMember = member(foreignClass);
        UUID corruptAttempt = attempt(quiz, foreignMember, EVENT.plusMinutes(1));
        assertThat(reader.readLatestGradedCandidates(classId, foreignMember, topicId)).isEmpty();
        assertThat(reader.readLatestGradedCandidates(foreignClass, foreignMember, topicId)).isEmpty();
        assertThat(read()).extracting(TopicQuizEvidenceReader.TopicQuizEvidence::attemptId)
                .containsExactly(attempt).doesNotContain(corruptAttempt);
        db.update("UPDATE chapter SET class_id=? WHERE chapter_id=(SELECT chapter_id FROM lesson WHERE lesson_id=?)",
                foreignClass, lessonId);
        assertThat(read()).isEmpty();
    }

    @Test
    void readerScoresFeedTheExistingPureCalculator() {
        scoredQuiz(EVENT, "3", "2");
        scoredQuiz(EVENT.plusMinutes(1), "3", "1");
        var scores = read().stream().map(TopicQuizEvidenceReader.TopicQuizEvidence::topicScore).toList();
        var result = new MasteryCalculator().calculateMastery(scores, List.of()).orElseThrow();
        assertThat(result.score()).isEqualByComparingTo("50.00");
        assertThat(result.level()).isEqualTo(vn.edu.hcmut.co3103.lms.enums.MasteryLevel.DEVELOPING);
    }

    @Test
    void nullScopeIdsAreRejected() {
        assertThatThrownBy(() -> reader.readLatestGradedCandidates(null, memberId, topicId)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> reader.readLatestGradedCandidates(classId, null, topicId)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> reader.readLatestGradedCandidates(classId, memberId, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void explainActualAggregateQueryOnRepresentativeFixture() {
        UUID quiz = quiz();
        question(quiz, 0, "5", topicId);
        question(quiz, 1, "5", topicId);
        for (int i = 0; i < 240; i++) attempt(quiz, memberId, EVENT.plusMinutes(i));
        List<String> plan = named.query("EXPLAIN (ANALYZE, BUFFERS) " + TopicQuizEvidenceReader.LATEST_TOPIC_POINTS,
                Map.of("classId", classId, "classMemberId", memberId, "skillTopicId", topicId),
                (row, index) -> row.getString(1));
        assertThat(read()).hasSize(3);
        assertThat(plan).anyMatch(line -> line.contains("Execution Time"));
        System.out.println("LMS9 core reader EXPLAIN (240 attempts, two mapped questions):\n" + String.join("\n", plan));
    }

    private List<TopicQuizEvidenceReader.TopicQuizEvidence> read() {
        return reader.readLatestGradedCandidates(classId, memberId, topicId);
    }

    private UUID course() {
        UUID tutor = user("TUTOR");
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO class(class_id,tutor_id,class_code,title,enrollment_policy) VALUES (?,?,?,'Course','OPEN')",
                id, tutor, id.toString().substring(0, 30));
        return id;
    }

    private UUID user(String role) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO lms_user_access(user_id,full_name,email,lms_role) VALUES (?,'Evidence test',?,?)",
                id, id + "@example.test", role);
        return id;
    }

    private UUID member(UUID course) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO class_member(class_member_id,class_id,student_id,status) VALUES (?,?,?,'ACTIVE')",
                id, course, user("STUDENT"));
        return id;
    }

    private UUID topic(UUID course) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO skill_topics(skill_topic_id,class_id,name,difficulty_level) VALUES (?,?,?,1)",
                id, course, id.toString());
        return id;
    }

    private UUID quiz() {
        UUID activity = UUID.randomUUID();
        db.update("INSERT INTO activity(activity_id,lesson_id,title,activity_type,sequence_order) VALUES (?,?,'Quiz','QUIZ',0)",
                activity, lessonId);
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO quiz(quiz_id,activity_id,time_limit_minutes,deadline,attempts_allowed) VALUES (?,?,30,?,1000)",
                id, activity, EVENT.plusDays(1));
        return id;
    }

    private UUID question(UUID quiz, int order, String points, UUID topic) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO question(question_id,quiz_id,question_type,content,points_awarded,order_index) VALUES (?,?,'TRUE_FALSE','Question',?,?)",
                id, quiz, decimal(points), order);
        if (topic != null) map(id, topic);
        return id;
    }

    private void map(UUID question, UUID topic) {
        db.update("INSERT INTO question_skill_topics(question_id,skill_topic_id) VALUES (?,?)", question, topic);
    }

    private UUID attempt(UUID quiz, UUID member, OffsetDateTime event) {
        UUID id = UUID.randomUUID();
        attempt(id, quiz, member, event);
        return id;
    }

    private void attempt(UUID id, UUID quiz, UUID member, OffsetDateTime event) {
        Integer number = db.queryForObject("SELECT COALESCE(MAX(attempt_number),0)+1 FROM quiz_attempt WHERE quiz_id=? AND class_member_id=?",
                Integer.class, quiz, member);
        db.update("INSERT INTO quiz_attempt(attempt_id,quiz_id,class_member_id,attempt_number,status,submitted_at,score,graded_at) VALUES (?,?,?,?,'SUBMITTED',?,100,?)",
                id, quiz, member, number, event, event.plusMinutes(1));
    }

    private void answer(UUID attempt, UUID question, String earned) {
        db.update("INSERT INTO attempt_answer(attempt_id,question_id,is_correct,points_earned) VALUES (?,?,false,?)",
                attempt, question, decimal(earned));
    }

    private UUID scoredQuiz(OffsetDateTime event, String possible, String earned) {
        UUID quiz = quiz();
        UUID q = question(quiz, 0, possible, topicId);
        UUID attempt = attempt(quiz, memberId, event);
        answer(attempt, q, earned);
        return attempt;
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
