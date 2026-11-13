package vn.edu.hcmut.co3103.lms.service.adaptive.recommendation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.LearnerMastery;
import vn.edu.hcmut.co3103.lms.enums.MasteryLevel;
import vn.edu.hcmut.co3103.lms.repository.adaptive.LearnerMasteryRepository;
import vn.edu.hcmut.co3103.lms.service.assessment.QuizAttemptService;
import vn.edu.hcmut.co3103.lms.service.progress.LessonCompletionService;
import vn.edu.hcmut.co3103.lms.service.progress.StudentProgressService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static vn.edu.hcmut.co3103.lms.service.adaptive.recommendation.RecommendationRanker.Reason.*;

@SpringBootTest(properties = "lms.quiz.expiry-scan-ms=3600000")
@Transactional
class RecommendationPostgresTest {
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired JdbcTemplate db;
    @Autowired RecommendationService recommendations;
    @Autowired EligibleActivityReader reader;
    @Autowired LearnerMasteryRepository masteries;
    @Autowired QuizAttemptService attempts;
    @Autowired LessonCompletionService completion;
    @Autowired StudentProgressService progress;

    UUID owner, student, course, member, chapter, lesson, a, b, c;
    Quiz first, second;

    @BeforeEach void seed() {
        assertThat(db.queryForObject("SELECT version()", String.class)).contains("PostgreSQL 18");
        owner = user("TUTOR"); student = user("STUDENT"); course = course(owner);
        member = member(course, student);
        chapter = chapter(course, 0); lesson = lesson(chapter, 0);
        a = topic(course); b = topic(course); c = topic(course);
        first = quiz(lesson, 0, a); second = quiz(lesson, 1, b);
    }

    @Test void weakMasterySelectsSuitablePracticeRatherThanEarlierUnrelatedActivity() {
        mastery(b, "0");
        var result = recommend();
        assertThat(result.activity().activityId()).isEqualTo(second.activity());
        assertThat(result.reason()).isEqualTo(ADDITIONAL_PRACTICE);
        assertThat(result.reinforcedTopicId()).isEqualTo(b);
    }

    @Test void weakPrerequisitePrecedesLowerScoringDependentWithoutBecomingAnAccessGate() {
        depends(b, a); mastery(a, "40"); mastery(b, "1");
        assertThat(recommend().activity().activityId()).isEqualTo(first.activity());
        // Existing LMS-5 access still permits the dependent Quiz.
        assertThat(attempts.startOrResume(second.id(), student).body().quizId()).isEqualTo(second.id());
    }

    @Test void transitivePrerequisiteWithNoPracticeForIntermediateIsStillConsidered() {
        var third = quiz(lesson, 2, c); depends(c, b); depends(b, a);
        db.update("UPDATE activity SET status='HIDDEN' WHERE activity_id=?", second.activity());
        mastery(a, "45"); mastery(c, "1");
        assertThat(recommend().activity().activityId()).isEqualTo(first.activity());
        assertThat(reader.read(course, member, OffsetDateTime.now())).extracting(RecommendationCandidate::activityId)
                .containsExactlyInAnyOrder(first.activity(), third.activity());
    }

    @Test void developingMasterySelectsReinforcement() {
        mastery(b, "79.99999999999999999999999999999999");
        assertThat(recommend().activity().activityId()).isEqualTo(second.activity());
        assertThat(recommend().reason()).isEqualTo(ADDITIONAL_PRACTICE);
    }

    @Test void sufficientMasteryProgressesToSubsequentEligibleActivity() {
        mastery(a, "80");
        assertThat(recommend().activity().activityId()).isEqualTo(second.activity());
        assertThat(recommend().reason()).isEqualTo(NORMAL_SEQUENCE);
    }

    @Test void noEvidenceUsesCanonicalSequence() {
        assertThat(recommend().activity().activityId()).isEqualTo(first.activity());
        assertThat(recommend().reason()).isEqualTo(NORMAL_SEQUENCE);
        assertThat(recommend().reinforcedTopicId()).isNull();
    }

    @Test void noSuitablePracticeUsesNormalSequenceWithoutTextOrDifficultyInference() {
        mastery(c, "0");
        db.update("UPDATE activity SET title='Practice for weak topic',description='reinforce everything' WHERE activity_id=?", second.activity());
        db.update("UPDATE skill_topics SET difficulty_level=5 WHERE skill_topic_id=?", c);
        assertThat(recommend().activity().activityId()).isEqualTo(first.activity());
        assertThat(recommend().reason()).isEqualTo(NORMAL_SEQUENCE);
    }

    @Test void explicitActivityAssociationIsHonoredWithoutDependingOnQuestionAssociation() {
        db.update("DELETE FROM question_skill_topics WHERE question_id IN (?,?)", first.question(), second.question());
        db.update("INSERT INTO activity_skill_topics(activity_id,skill_topic_id) VALUES (?,?)", second.activity(), a);
        mastery(a, "10");
        assertThat(recommend().activity().activityId()).isEqualTo(second.activity());
        assertThat(recommend().activity().topicIds()).containsExactly(a);
    }

    @Test void questionAssociationIsHonoredWithoutMaterializedActivityAssociation() {
        assertThat(db.queryForObject("SELECT count(*) FROM activity_skill_topics WHERE activity_id=?", Integer.class, second.activity())).isZero();
        mastery(b, "20");
        assertThat(recommend().activity().activityId()).isEqualTo(second.activity());
    }

    @Test void overlappingExplicitAndQuestionMultiTopicMappingsDoNotDuplicateCandidates() {
        db.update("INSERT INTO question_skill_topics(question_id,skill_topic_id) VALUES (?,?)", second.question(), a);
        db.update("INSERT INTO activity_skill_topics(activity_id,skill_topic_id) VALUES (?,?),(?,?)", second.activity(), a, second.activity(), b);
        mastery(b, "0");
        var candidates = reader.read(course, member, OffsetDateTime.now());
        assertThat(candidates).hasSize(2);
        assertThat(candidates).filteredOn(q -> q.activityId().equals(second.activity())).singleElement()
                .satisfies(q -> assertThat(q.topicIds()).containsExactlyInAnyOrder(a, b));
        assertThat(recommend().activity().activityId()).isEqualTo(second.activity());
    }

    @Test void hiddenActivityIsExcludedEvenWhenItsMasteryIsWeak() {
        mastery(a, "0"); db.update("UPDATE activity SET status='HIDDEN' WHERE activity_id=?", first.activity());
        assertThat(recommend().activity().activityId()).isEqualTo(second.activity());
    }

    @ParameterizedTest @ValueSource(strings = {"lesson", "chapter"})
    void hiddenParentExcludesPublishedDescendants(String table) {
        db.update("UPDATE " + table + " SET status='HIDDEN' WHERE " + table + "_id=?", table.equals("lesson") ? lesson : chapter);
        assertEmpty(NO_ELIGIBLE_ACTIVITY);
    }

    @Test void archivedClassHasNoNewActionAndExistingProgressHistoryRemainsReadable() {
        db.update("UPDATE class SET status='ARCHIVED' WHERE class_id=?", course);
        assertEmpty(CLASS_ARCHIVED);
        assertThat(progress.getClassProgress(course, student).classId()).isEqualTo(course);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void expiredAndNotYetOpenQuizzesAreExcluded(boolean expired) {
        if (expired) db.update("UPDATE quiz SET deadline=? WHERE quiz_id=?", OffsetDateTime.now().minusMinutes(1), first.id());
        else db.update("UPDATE quiz SET opening_time=? WHERE quiz_id=?", OffsetDateTime.now().plusHours(1), first.id());
        mastery(a, "0");
        assertThat(recommend().activity().activityId()).isEqualTo(second.activity());
        assertThatThrownBy(() -> attempts.startOrResume(first.id(), student)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("QUIZ_NOT_AVAILABLE");
    }

    @Test void exhaustedAttemptsAreExcludedAndCanonicalStartAgrees() {
        db.update("UPDATE quiz SET attempts_allowed=1 WHERE quiz_id=?", first.id());
        submitted(first, member); mastery(a, "0");
        assertThat(recommend().activity().activityId()).isEqualTo(second.activity());
        assertThatThrownBy(() -> attempts.startOrResume(first.id(), student)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ATTEMPT_LIMIT_REACHED");
    }

    @Test void remainingAttemptsPermitReinforcementAndCanonicalRetryAgrees() {
        submitted(first, member); mastery(a, "0");
        assertThat(recommend().activity().activityId()).isEqualTo(first.activity());
        var retry = attempts.startOrResume(first.id(), student);
        assertThat(retry.body().attemptNumber()).isEqualTo(2);
    }

    @Test void pendingGradesConsumeAttemptSlotsAndAreNotInventedAsMastery() {
        submitted(first, member);
        assertThat(recommend().activity().activityId()).isEqualTo(second.activity());
        assertThat(masteries.findByClassMemberId(member)).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"INACTIVE", "PENDING"})
    void nonActiveMembershipIsDenied(String status) {
        db.update("UPDATE class_member SET status=? WHERE class_member_id=?", status, member);
        assertForbidden();
        assertThat(reader.read(course, member, OffsetDateTime.now())).isEmpty();
    }

    @Test void suspendedLocalAccessIsDenied() {
        db.update("UPDATE lms_user_access SET access_status='SUSPENDED' WHERE user_id=?", student);
        assertForbidden(); assertThat(reader.read(course, member, OffsetDateTime.now())).isEmpty();
    }

    @Test void tutorRoleDoesNotSubstituteForTheLearnersLocalStudentRole() {
        assertThatThrownBy(() -> recommendations.recommend(course, owner)).isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403));
    }

    @Test void crossClassActivitiesAndForeignTopicMappingsCannotLeakIntoRecommendations() {
        UUID foreign = course(owner), foreignChapter = chapter(foreign, 0), foreignLesson = lesson(foreignChapter, 0), foreignTopic = topic(foreign);
        var outsider = quiz(foreignLesson, 0, a); // Deliberately malformed cross-class Question mapping.
        db.update("INSERT INTO activity_skill_topics(activity_id,skill_topic_id) VALUES (?,?)", first.activity(), foreignTopic);
        masteries.saveAndFlush(new LearnerMastery(member, foreignTopic, BigDecimal.ZERO, MasteryLevel.WEAK));
        assertThat(reader.read(course, member, OffsetDateTime.now())).extracting(RecommendationCandidate::activityId)
                .containsExactlyInAnyOrder(first.activity(), second.activity()).doesNotContain(outsider.activity());
        assertThat(recommend().activity().activityId()).isEqualTo(first.activity());
        assertThat(recommend().reason()).isEqualTo(NORMAL_SEQUENCE);
        assertThat(recommend().activity().topicIds()).doesNotContain(foreignTopic);
        assertThatThrownBy(() -> recommendations.recommend(foreign, student)).isInstanceOf(ResponseStatusException.class);
    }

    @Test void anotherMembersMasteryAndAttemptsDoNotAffectTheCaller() {
        UUID other = user("STUDENT"), otherMember = member(course, other);
        masteries.saveAndFlush(new LearnerMastery(otherMember, b, BigDecimal.ZERO, MasteryLevel.WEAK));
        db.update("UPDATE quiz SET attempts_allowed=1 WHERE quiz_id=?", first.id()); submitted(first, otherMember);
        assertThat(recommend().activity().activityId()).isEqualTo(first.activity());
        assertThat(recommend().reason()).isEqualTo(NORMAL_SEQUENCE);
    }

    @Test void identicalCandidateDataAlwaysHasStableUuidOrdering() {
        db.update("UPDATE activity SET sequence_order=0 WHERE activity_id=?", second.activity());
        UUID expected = first.activity().toString().compareTo(second.activity().toString()) < 0 ? first.activity() : second.activity();
        for (int n = 0; n < 5; n++) assertThat(recommend().activity().activityId()).isEqualTo(expected);
    }

    @Test void noEligibleCandidateReturnsExplicitEmptyResult() {
        db.update("UPDATE activity SET status='HIDDEN' WHERE lesson_id=?", lesson); assertEmpty(NO_ELIGIBLE_ACTIVITY);
    }

    @Test void activeQuizResumeRemainsOwnedByLms7AndDoesNotCreateAnotherAttempt() {
        var started = attempts.startOrResume(first.id(), student).body();
        mastery(a, "0");
        assertThat(recommend().activity().activityId()).isEqualTo(second.activity());
        assertThat(progress.getResumeTarget(course, student).targetId()).isEqualTo(started.attemptId());
        assertThat(db.queryForObject("SELECT count(*) FROM quiz_attempt WHERE class_member_id=?", Integer.class, member)).isEqualTo(1);
    }

    @Test void expiredInProgressAttemptWaitsForCanonicalExpiryWorkerWithoutRecommendationSideEffects() {
        var started = attempts.startOrResume(first.id(), student).body();
        em.flush();
        db.update("UPDATE quiz_attempt SET started_at=? WHERE attempt_id=?", OffsetDateTime.now().minusHours(1), started.attemptId());
        em.clear(); // The canonical service must observe the fixture's persisted timestamp.
        assertThat(recommend().activity().activityId()).isEqualTo(second.activity());
        assertThat(db.queryForObject("SELECT status FROM quiz_attempt WHERE attempt_id=?", String.class, started.attemptId())).isEqualTo("IN_PROGRESS");
        assertThatThrownBy(() -> attempts.startOrResume(first.id(), student)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("QUIZ_NOT_AVAILABLE");
    }

    @Test void lessonCompletionDoesNotCompleteAnUnattemptedQuizOrFabricateMastery() {
        completion.markComplete(course, lesson, student);
        assertThat(recommend().activity().activityId()).isEqualTo(first.activity());
        assertThat(masteries.findByClassMemberId(member)).isEmpty();
    }

    @Test void fallbackUsesLessonProgressWithoutReplacingLastVisitResume() {
        UUID nextLesson = lesson(chapter, 1); var next = quiz(nextLesson, 0, c);
        completion.markComplete(course, lesson, student);
        assertThat(recommend().activity().activityId()).isEqualTo(next.activity());
        assertThat(progress.getResumeTarget(course, student).targetId()).isEqualTo(nextLesson);
    }

    @Test void canonicalHierarchyOrderPrecedesLocalActivityOrder() {
        UUID earlierChapter = chapter(course, 1), laterChapter = chapter(course, 2);
        var earlier = quiz(lesson(earlierChapter, 20), 99, a); quiz(lesson(laterChapter, 0), 0, b);
        db.update("UPDATE activity SET status='HIDDEN' WHERE lesson_id=?", lesson);
        assertThat(recommend().activity().activityId()).isEqualTo(earlier.activity());
    }

    @Test void questionWithInvalidOptionsAndMissingQuizConfigurationAreNotCandidates() {
        db.update("UPDATE question_option SET is_correct=false WHERE question_id=?", first.question());
        UUID missing = UUID.randomUUID();
        db.update("INSERT INTO activity(activity_id,lesson_id,title,activity_type,sequence_order) VALUES (?,?,'Missing','QUIZ',0)", missing, lesson);
        assertThat(recommend().activity().activityId()).isEqualTo(second.activity());
    }

    @Test void emptyQuizIsNotEligibleAndAssignmentsRemainDeferred() {
        db.update("DELETE FROM question_skill_topics WHERE question_id=?", first.question());
        db.update("DELETE FROM question_option WHERE question_id=?", first.question()); db.update("DELETE FROM question WHERE question_id=?", first.question());
        db.update("UPDATE activity SET activity_type='ASSIGNMENT' WHERE activity_id=?", second.activity());
        assertEmpty(NO_ELIGIBLE_ACTIVITY);
    }

    @Test void recommendationDoesNotMutateAssessmentProgressSignalsOrMastery() {
        mastery(b, "0");
        var before = db.queryForList("SELECT * FROM learner_mastery WHERE class_member_id=?", member);
        recommend(); recommend();
        for (String table : List.of("quiz_attempt", "completion_mark", "last_visit", "learning_signal"))
            assertThat(db.queryForObject("SELECT count(*) FROM " + table + " WHERE class_member_id=?", Integer.class, member)).isZero();
        assertThat(db.queryForList("SELECT * FROM learner_mastery WHERE class_member_id=?", member)).isEqualTo(before);
    }

    private RecommendationRanker.Result recommend() { return recommendations.recommend(course, student); }
    private void assertEmpty(RecommendationRanker.Reason reason) { var result = recommend(); assertThat(result.activity()).isNull(); assertThat(result.reason()).isEqualTo(reason); }
    private void assertForbidden() { assertThatThrownBy(this::recommend).isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(403)); }
    private void mastery(UUID topic, String value) {
        BigDecimal score = new BigDecimal(value);
        MasteryLevel level = score.compareTo(new BigDecimal("50")) < 0 ? MasteryLevel.WEAK : score.compareTo(new BigDecimal("80")) < 0 ? MasteryLevel.DEVELOPING : MasteryLevel.SUFFICIENT;
        masteries.saveAndFlush(new LearnerMastery(member, topic, score, level));
    }
    private void depends(UUID target, UUID prerequisite) { db.update("INSERT INTO skill_prerequisites(skill_topic_id,prerequisite_skill_topic_id) VALUES (?,?)", target, prerequisite); }
    private void submitted(Quiz quiz, UUID learner) {
        db.update("INSERT INTO quiz_attempt(quiz_id,class_member_id,attempt_number,status,submitted_at) VALUES (?,?,1,'SUBMITTED',?)", quiz.id(), learner, OffsetDateTime.now().minusHours(1));
    }
    private UUID user(String role) { UUID id = UUID.randomUUID(); db.update("INSERT INTO lms_user_access(user_id,full_name,email,lms_role) VALUES (?,'Recommendation',?,?)", id, id + "@example.test", role); return id; }
    private UUID course(UUID tutor) { UUID id = UUID.randomUUID(); db.update("INSERT INTO class(class_id,tutor_id,class_code,title,enrollment_policy) VALUES (?,?,?,'Course','OPEN')", id, tutor, id.toString().substring(0, 30)); return id; }
    private UUID member(UUID course, UUID student) { UUID id = UUID.randomUUID(); db.update("INSERT INTO class_member(class_member_id,class_id,student_id,status) VALUES (?,?,?,'ACTIVE')", id, course, student); return id; }
    private UUID chapter(UUID course, int order) { UUID id = UUID.randomUUID(); db.update("INSERT INTO chapter(chapter_id,class_id,title,sequence_order) VALUES (?,?,'Chapter',?)", id, course, order); return id; }
    private UUID lesson(UUID chapter, int order) { UUID id = UUID.randomUUID(); db.update("INSERT INTO lesson(lesson_id,chapter_id,title,sequence_order) VALUES (?,?,'Lesson',?)", id, chapter, order); return id; }
    private UUID topic(UUID course) { UUID id = UUID.randomUUID(); db.update("INSERT INTO skill_topics(skill_topic_id,class_id,name,difficulty_level) VALUES (?,?,?,1)", id, course, id.toString()); return id; }
    private Quiz quiz(UUID lesson, int order, UUID topic) {
        UUID activity = UUID.randomUUID(), quiz = UUID.randomUUID(), question = UUID.randomUUID();
        db.update("INSERT INTO activity(activity_id,lesson_id,title,activity_type,sequence_order) VALUES (?,?,'Quiz','QUIZ',?)", activity, lesson, order);
        db.update("INSERT INTO quiz(quiz_id,activity_id,time_limit_minutes,attempts_allowed,deadline) VALUES (?,?,30,3,?)", quiz, activity, OffsetDateTime.now().plusDays(1));
        db.update("INSERT INTO question(question_id,quiz_id,question_type,content,points_awarded,order_index) VALUES (?,?,'MCQ_SINGLE','Question',1,0)", question, quiz);
        db.update("INSERT INTO question_option(question_id,content,is_correct,order_index) VALUES (?,'Right',true,0),(?,'Wrong',false,1)", question, question);
        db.update("INSERT INTO question_skill_topics(question_id,skill_topic_id) VALUES (?,?)", question, topic);
        return new Quiz(quiz, activity, question);
    }
    private record Quiz(UUID id, UUID activity, UUID question) { }
}
