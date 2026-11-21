package vn.edu.hcmut.co3103.lms.service.adaptive;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.hcmut.co3103.lms.dto.request.GradeSubmissionRequest;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.ClassAccessAdapter;
import vn.edu.hcmut.co3103.lms.service.assessment.AssignmentService;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Canonical Flyway/PostgreSQL schema; transactions roll back except explicit committed recovery tests. */
@SpringBootTest(properties = "lms.quiz.expiry-scan-ms=3600000")
@Transactional
class TopicAssignmentEvidenceReaderPostgresTest {
    @Autowired JdbcTemplate db;
    @Autowired EntityManager em;
    @Autowired TopicAssignmentEvidenceReader reader;
    @Autowired MasteryRecalculationService mastery;
    @Autowired AssignmentService grading;
    @Autowired SkillTopicAssociationService associations;
    @Autowired ClassRepository classes;
    @Autowired PlatformTransactionManager transactions;
    UUID owner, student, course, chapter, lesson, member, topic;
    final List<UUID> users = new ArrayList<>();
    final List<UUID> courses = new ArrayList<>();
    static final OffsetDateTime EVENT = OffsetDateTime.parse("2026-11-01T10:00:00Z");

    @BeforeEach void seed() {
        assertThat(db.queryForObject("SELECT version()", String.class)).contains("PostgreSQL 18");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            owner = user("TUTOR"); student = user("STUDENT"); course = course();
            chapter = UUID.randomUUID(); lesson = UUID.randomUUID(); member = UUID.randomUUID();
            db.update("INSERT INTO chapter(chapter_id,class_id,title,sequence_order) VALUES (?,?,'Chapter',0)", chapter, course);
            db.update("INSERT INTO lesson(lesson_id,chapter_id,title,sequence_order) VALUES (?,?,'Lesson',0)", lesson, chapter);
            db.update("INSERT INTO class_member(class_member_id,class_id,student_id,status) VALUES (?,?,?,'ACTIVE')", member, course, student);
            topic = topic(course);
        });
    }

    @AfterEach void cleanupCommittedTests() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) return;
        for (UUID c : courses) {
            String activities = "SELECT a.activity_id FROM activity a JOIN lesson l ON l.lesson_id=a.lesson_id JOIN chapter ch ON ch.chapter_id=l.chapter_id WHERE ch.class_id=?";
            db.update("DELETE FROM assignment WHERE activity_id IN (" + activities + ")", c);
            db.update("DELETE FROM activity_skill_topics WHERE skill_topic_id IN (SELECT skill_topic_id FROM skill_topics WHERE class_id=?)", c);
            db.update("DELETE FROM skill_topics WHERE class_id=?", c);
            db.update("DELETE FROM activity WHERE activity_id IN (" + activities + ")", c);
            db.update("DELETE FROM lesson WHERE chapter_id IN (SELECT chapter_id FROM chapter WHERE class_id=?)", c);
            db.update("DELETE FROM chapter WHERE class_id=?", c);
            db.update("DELETE FROM class_member WHERE class_id=?", c);
            db.update("DELETE FROM class WHERE class_id=?", c);
        }
        for (UUID u : users) {
            db.update("DELETE FROM notification WHERE recipient_id=?", u);
            db.update("DELETE FROM lms_user_access WHERE user_id=?", u);
        }
    }

    @Test void onlyGradedMappedSourcesContributeAndZeroIsRealEvidence() {
        UUID zero = submission("0", EVENT); UUID hundred = submission("100", EVENT.plusMinutes(1));
        ungraded(assignment(topic), EVENT.plusDays(1));
        UUID unmapped = assignment(); insert(UUID.randomUUID(), unmapped, member, "70", EVENT.plusDays(2));
        assertThat(read()).extracting(TopicAssignmentEvidenceReader.TopicAssignmentEvidence::submissionId).containsExactly(hundred, zero);
        assertThat(read()).extracting(TopicAssignmentEvidenceReader.TopicAssignmentEvidence::topicScore)
                .containsExactly(new BigDecimal("100.00"), new BigDecimal("0.00"));
        assertThat(read()).allSatisfy(e -> { assertThat(e.classMemberId()).isEqualTo(member); assertThat(e.skillTopicId()).isEqualTo(topic); });
    }

    @Test void multipleTopicsAndReferenceAttachmentsDoNotDuplicateEvidence() {
        UUID other = topic(course); UUID a = assignment(topic, other);
        UUID s = insert(UUID.randomUUID(), a, member, "65", EVENT);
        for (int i=0; i<3; i++) db.update("INSERT INTO assignment_attachment(assignment_id,file_name,storage_path,mime_type,size_bytes) VALUES (?,'reference','unused','text/plain',1)", a);
        assertThat(read()).extracting(TopicAssignmentEvidenceReader.TopicAssignmentEvidence::submissionId).containsExactly(s);
        assertThat(reader.readLatestGradedCandidates(course, member, other)).hasSize(1);
        assertThat(reader.readLatestGradedCandidates(course, member, other).getFirst().topicScore()).isEqualByComparingTo("65");
    }

    @Test void eligibilityAndMappingAreAppliedBeforeLatestThreeAcrossAssignments() {
        var ids = new ArrayList<UUID>();
        for (int i=0; i<5; i++) ids.add(submission(Integer.toString(i*20), EVENT.plusMinutes(i)));
        ungraded(assignment(topic), EVENT.plusDays(1));
        insert(UUID.randomUUID(), assignment(), member, "100", EVENT.plusDays(2));
        assertThat(read()).extracting(TopicAssignmentEvidenceReader.TopicAssignmentEvidence::submissionId).containsExactly(ids.get(4), ids.get(3), ids.get(2));
        assertThat(reader.readAllGradedCandidates(course, member, topic)).hasSize(5);
    }

    @Test void postgresUuidOrderingIsDeterministicForEqualSubmissionTimes() {
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID middle = UUID.fromString("80000000-0000-0000-0000-000000000000");
        insert(low, assignment(topic), member, "10", EVENT);
        insert(high, assignment(topic), member, "20", EVENT);
        insert(middle, assignment(topic), member, "30", EVENT);
        assertThat(read()).extracting(TopicAssignmentEvidenceReader.TopicAssignmentEvidence::submissionId).containsExactly(high, middle, low);
    }

    @ParameterizedTest @ValueSource(strings={"activity", "lesson", "chapter", "archive", "membership", "local-access"})
    void historicalEvidenceIgnoresCurrentVisibilityAndAuthorization(String change) {
        UUID s = submission("75", EVENT);
        switch(change) {
            case "activity" -> db.update("UPDATE activity SET status='HIDDEN' WHERE lesson_id=?", lesson);
            case "lesson" -> db.update("UPDATE lesson SET status='HIDDEN' WHERE lesson_id=?", lesson);
            case "chapter" -> db.update("UPDATE chapter SET status='HIDDEN' WHERE chapter_id=?", chapter);
            case "archive" -> db.update("UPDATE class SET status='ARCHIVED' WHERE class_id=?", course);
            case "membership" -> db.update("UPDATE class_member SET status='INACTIVE' WHERE class_member_id=?", member);
            case "local-access" -> db.update("UPDATE lms_user_access SET access_status='SUSPENDED' WHERE user_id=?", student);
        }
        assertThat(read()).extracting(TopicAssignmentEvidenceReader.TopicAssignmentEvidence::submissionId).containsExactly(s);
        mastery.reconcileAssignments(course);
        assertScore(topic, "75");
    }

    @Test void wrongClassMemberTopicAndCrossHierarchyAreRejected() {
        UUID s = submission("80", EVENT); UUID foreign = course(); UUID foreignTopic = topic(foreign);
        UUID foreignMember = UUID.randomUUID();
        db.update("INSERT INTO class_member(class_member_id,class_id,student_id,status) VALUES (?,?,?,'ACTIVE')", foreignMember, foreign, student);
        assertThat(reader.readLatestGradedCandidates(foreign, member, topic)).isEmpty();
        assertThat(reader.readLatestGradedCandidates(course, foreignMember, topic)).isEmpty();
        assertThat(reader.readLatestGradedCandidates(course, member, foreignTopic)).isEmpty();
        db.update("UPDATE submission SET class_member_id=? WHERE submission_id=?", foreignMember, s);
        assertThat(read()).isEmpty();
        db.update("UPDATE submission SET class_member_id=? WHERE submission_id=?", member, s);
        db.update("UPDATE chapter SET class_id=? WHERE chapter_id=?", foreign, chapter);
        assertThat(read()).isEmpty();
    }

    @Test void crossClassTopicMappingCannotLeakAndWrongActivityTypeIsExcluded() {
        UUID a = assignment(topic); insert(UUID.randomUUID(), a, member, "90", EVENT);
        UUID foreign = course(); UUID foreignTopic = topic(foreign);
        db.update("INSERT INTO activity_skill_topics(activity_id,skill_topic_id) SELECT activity_id,? FROM assignment WHERE assignment_id=?", foreignTopic, a);
        assertThat(reader.readLatestGradedCandidates(course, member, foreignTopic)).isEmpty();
        assertThat(reader.readLatestGradedCandidates(foreign, member, foreignTopic)).isEmpty();
        db.update("UPDATE activity SET activity_type='QUIZ' WHERE activity_id=(SELECT activity_id FROM assignment WHERE assignment_id=?)", a);
        assertThat(read()).isEmpty();
    }

    @Test void storedPercentageRoundsOnceAndNoMaximumOrDenominatorIsInvented() {
        submission("66.665", EVENT);
        assertThat(read().getFirst().topicScore()).isEqualTo(new BigDecimal("66.67"));
        assertThat(db.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='assignment' AND column_name IN ('max_score','maximum_score')", Integer.class)).isZero();
    }

    @Test void nullInputsFailBeforeJdbc() {
        assertThatThrownBy(() -> reader.readLatestGradedCandidates(null, member, topic)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> reader.readAllGradedCandidates(course, null, topic)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> reader.readAllGradedCandidates(course, member, null)).isInstanceOf(NullPointerException.class);
    }

    @Test void assignmentOnlyAveragesUseDecimal128AndLatestThree() {
        submission("100", EVENT); submission("0", EVENT.plusMinutes(1));
        submission("100", EVENT.plusMinutes(2)); submission("100", EVENT.plusMinutes(3));
        mastery.reconcileAssignments(course);
        BigDecimal expected = new BigDecimal("200").divide(new BigDecimal("3"), MathContext.DECIMAL128);
        assertThat(score(topic)).isEqualByComparingTo(expected);
        assertThat(signalCount()).isEqualTo(4);
    }

    @Test void componentsAreEquallyWeightedInsteadOfPoolingRecordsAndQuizSignalsSurvive() {
        quizEvidence("90", 3); submission("30", EVENT);
        mastery.reconcileAssignments(course);
        assertScore(topic, "60");
        assertThat(db.queryForObject("SELECT count(*) FROM learning_signal WHERE class_member_id=? AND signal_type='QUIZ_RESULT'", Integer.class, member)).isEqualTo(3);
        assertThat(signalCount()).isEqualTo(1);
        UUID signal = db.queryForObject("SELECT learning_signal_id FROM learning_signal WHERE class_member_id=? AND signal_type='ASSIGNMENT_RESULT'", UUID.class, member);
        mastery.reconcileAssignments(course);
        assertScore(topic, "60");
        assertThat(db.queryForObject("SELECT learning_signal_id FROM learning_signal WHERE class_member_id=? AND signal_type='ASSIGNMENT_RESULT'", UUID.class, member)).isEqualTo(signal);
    }

    @Test void quizOnlyAndNeitherComponentKeepTheirExistingSemantics() {
        quizEvidence("90", 1); mastery.reconcileAssignments(course);
        // A pair with Quiz-only history is rebuilt by the existing pair boundary.
        mastery.recalculatePair(course, member, topic); assertScore(topic, "90");
        db.update("DELETE FROM attempt_answer WHERE attempt_id IN (SELECT attempt_id FROM quiz_attempt WHERE class_member_id=?)", member);
        db.update("DELETE FROM quiz_attempt WHERE class_member_id=?", member);
        mastery.reconcileAssignments(course);
        assertThat(score(topic)).isNull(); assertThat(signalCount()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"0", "49.99", "50", "79.99", "80", "100"})
    void assignmentOnlyThresholdsUseActualEvidence(String value) {
        submission(value, EVENT); mastery.reconcileAssignments(course); assertScore(topic, value);
        String expected = new BigDecimal(value).compareTo(new BigDecimal("50"))<0 ? "WEAK" : new BigDecimal(value).compareTo(new BigDecimal("80"))<0 ? "DEVELOPING" : "SUFFICIENT";
        assertThat(db.queryForObject("SELECT mastery_level FROM learner_mastery WHERE class_member_id=? AND skill_topic_id=?", String.class, member, topic)).isEqualTo(expected);
    }

    @Test void canonicalGradeAndRegradeUpdateOneSignalAndKeepOriginalSubmissionOrdering() {
        UUID first = ungraded(assignment(topic), EVENT);
        for (int i=1; i<=3; i++) submission("90", EVENT.plusMinutes(i));
        grading.grade(first, owner, new GradeSubmissionRequest(new BigDecimal("20"), "first", 0L));
        UUID signal = db.queryForObject("SELECT learning_signal_id FROM learning_signal WHERE source_id=?", UUID.class, first);
        OffsetDateTime graded = db.queryForObject("SELECT graded_at FROM submission WHERE submission_id=?", OffsetDateTime.class, first);
        long version = db.queryForObject("SELECT version FROM submission WHERE submission_id=?", Long.class, first);
        grading.grade(first, owner, new GradeSubmissionRequest(new BigDecimal("100"), "regrade", version));
        assertThat(db.queryForObject("SELECT submitted_at FROM submission WHERE submission_id=?", OffsetDateTime.class, first)).isEqualTo(EVENT);
        assertThat(db.queryForObject("SELECT graded_at FROM submission WHERE submission_id=?", OffsetDateTime.class, first)).isAfterOrEqualTo(graded);
        assertThat(db.queryForObject("SELECT learning_signal_id FROM learning_signal WHERE source_id=?", UUID.class, first)).isEqualTo(signal);
        assertThat(db.queryForObject("SELECT signal_value FROM learning_signal WHERE source_id=?", BigDecimal.class, first)).isEqualByComparingTo("100");
        assertThat(db.queryForObject("SELECT recorded_at FROM learning_signal WHERE source_id=?", OffsetDateTime.class, first)).isEqualTo(EVENT);
        assertThat(read()).extracting(TopicAssignmentEvidenceReader.TopicAssignmentEvidence::submissionId).doesNotContain(first);
        assertScore(topic, "90"); assertThat(signalCount()).isEqualTo(4);
    }

    @Test void ungradedSubmissionProducesNoSignalsAndNoWeakMastery() {
        UUID s=ungraded(assignment(topic), EVENT); mastery.synchronizeSubmission(s);
        assertThat(signalCount()).isZero(); assertThat(score(topic)).isNull();
    }

    @Test void mappingAddsReplacesRemovesAndRepeatedCallsAreIdempotent() {
        UUID a=assignment(topic); UUID s=insert(UUID.randomUUID(), a, member, "70", EVENT);
        UUID activity=db.queryForObject("SELECT activity_id FROM assignment WHERE assignment_id=?", UUID.class,a);
        UUID other=topic(course);
        mastery.synchronizeSubmission(s); remap(activity, topic,other); remap(activity,topic,other);
        assertScore(topic,"70"); assertScore(other,"70"); assertThat(signalCount()).isEqualTo(2);
        remap(activity,other); assertThat(score(topic)).isNull(); assertScore(other,"70");
        remap(activity); assertThat(score(other)).isNull(); assertThat(signalCount()).isZero();
    }

    @Test void historicalReconciliationDiscoversOldGradesAndCleansDeletedSources() {
        UUID s=submission("0",EVENT); assertThat(score(topic)).isNull();
        mastery.reconcileAssignments(course); assertScore(topic,"0");
        db.update("DELETE FROM submission WHERE submission_id=?", s);
        mastery.reconcileAssignments(course); mastery.reconcileAssignments(course);
        assertThat(signalCount()).isZero(); assertThat(score(topic)).isNull();
    }

    @Test void removingAssignmentComponentFallsBackToQuizRatherThanDeletingItsMastery() {
        quizEvidence("90",1); UUID s=submission("30",EVENT); mastery.reconcileAssignments(course); assertScore(topic,"60");
        db.update("DELETE FROM submission WHERE submission_id=?",s); mastery.reconcileAssignments(course); assertScore(topic,"90");
        assertThat(signalCount()).isZero();
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void synchronizationFailureRollsBackGradeVersionAndDerivedStateAndReconciliationCanRetry() {
        UUID s=new TransactionTemplate(transactions).execute(tx->ungraded(assignment(topic),EVENT));
        String name="reject_signal_"+s.toString().replace("-","");
        db.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.source_id='"+s+"'::uuid THEN RAISE EXCEPTION 'fixture rejection'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER "+name+" BEFORE INSERT ON learning_signal FOR EACH ROW EXECUTE FUNCTION "+name+"()");
        try {
            assertThatThrownBy(()->grading.grade(s,owner,new GradeSubmissionRequest(new BigDecimal("70"),null,0L))).isInstanceOf(RuntimeException.class);
            assertThat(db.queryForObject("SELECT status FROM submission WHERE submission_id=?",String.class,s)).isEqualTo("SUBMITTED");
            assertThat(db.queryForObject("SELECT version FROM submission WHERE submission_id=?",Long.class,s)).isZero();
            assertThat(db.queryForObject("SELECT score FROM submission WHERE submission_id=?",BigDecimal.class,s)).isNull();
            assertThat(signalCount()).isZero(); assertThat(score(topic)).isNull();
            // Existing historical source, separate failed Class rebuild: source remains, all derived writes roll back.
            db.update("UPDATE submission SET status='GRADED',score=70,graded_by=?,graded_at=? WHERE submission_id=?",owner,EVENT.plusMinutes(1),s);
            assertThatThrownBy(()->mastery.reconcileAssignments(course)).isInstanceOf(RuntimeException.class);
            assertThat(signalCount()).isZero(); assertThat(score(topic)).isNull();
        } finally { db.execute("DROP TRIGGER "+name+" ON learning_signal"); db.execute("DROP FUNCTION "+name+"()"); }
        mastery.reconcileAssignments(course); mastery.reconcileAssignments(course); assertScore(topic,"70"); assertThat(signalCount()).isEqualTo(1);
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void gradingAndMappingSerializeOnTheCanonicalClassLock() throws Exception {
        UUID a=new TransactionTemplate(transactions).execute(tx->assignment(topic));
        UUID s=new TransactionTemplate(transactions).execute(tx->ungraded(a,EVENT));
        UUID other=new TransactionTemplate(transactions).execute(tx->topic(course));
        UUID activity=db.queryForObject("SELECT activity_id FROM assignment WHERE assignment_id=?",UUID.class,a);
        CountDownLatch started=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            final Future<?>[] mapping=new Future<?>[1];
            new TransactionTemplate(transactions).executeWithoutResult(tx->{
                classes.findByIdForUpdate(course).orElseThrow();
                mapping[0]=pool.submit(()->{started.countDown();remap(activity,other);});
                try { assertThat(started.await(5,TimeUnit.SECONDS)).isTrue(); assertThatThrownBy(()->mapping[0].get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                grading.grade(s,owner,new GradeSubmissionRequest(new BigDecimal("65"),null,0L));
            });
            mapping[0].get(10,TimeUnit.SECONDS);
        }
        assertThat(score(topic)).isNull(); assertScore(other,"65"); assertThat(signalCount()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"-0.01", "100.01"})
    void canonicalGradingRejectsInvalidPercentages(String value) {
        UUID s=ungraded(assignment(topic),EVENT);
        assertThatThrownBy(()->grading.grade(s,owner,new GradeSubmissionRequest(new BigDecimal(value),null,0L)))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(read()).isEmpty(); assertThat(signalCount()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"score", "graded_at", "submitted_at"})
    void schemaRejectsIncompleteGradedEvidenceWithoutWeakeningConstraints(String column) {
        UUID s=submission("70",EVENT);
        db.execute("DO $$ BEGIN BEGIN UPDATE submission SET "+column+"=NULL WHERE submission_id='"+s+"'::uuid; "
                +"EXCEPTION WHEN check_violation OR not_null_violation THEN RETURN; END; RAISE EXCEPTION 'Expected constraint rejection'; END $$");
        assertThat(read()).hasSize(1); assertThat(read().getFirst().topicScore()).isEqualByComparingTo("70");
    }

    @ParameterizedTest @ValueSource(ints={1,2,3})
    void componentCountsRemainIndependent(int count) {
        quizEvidence("90",3);
        for(int i=0;i<count;i++) submission("30",EVENT.plusMinutes(i));
        mastery.reconcileAssignments(course); assertScore(topic,"60");
    }

    @Test void highPrecisionBelowEightyDoesNotClassifyUsingRoundedDisplayValue() {
        submission("79.99",EVENT);submission("80",EVENT.plusMinutes(1));submission("80",EVENT.plusMinutes(2));
        mastery.reconcileAssignments(course);
        assertThat(score(topic)).isEqualByComparingTo(new BigDecimal("239.99").divide(new BigDecimal("3"),MathContext.DECIMAL128));
        assertThat(score(topic).setScale(2,java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("80");
        assertThat(db.queryForObject("SELECT mastery_level FROM learner_mastery WHERE class_member_id=? AND skill_topic_id=?",String.class,member,topic)).isEqualTo("DEVELOPING");
    }

    @Test void classReconciliationLeavesForeignSignalsAndMasteryUntouched() {
        submission("70",EVENT); UUID foreign=course(), ft=topic(foreign), fm=UUID.randomUUID(), source=UUID.randomUUID();
        db.update("INSERT INTO class_member(class_member_id,class_id,student_id,status) VALUES (?,?,?,'ACTIVE')",fm,foreign,student);
        db.update("INSERT INTO learning_signal(class_member_id,skill_topic_id,signal_type,source_type,source_id,signal_value,recorded_at) VALUES (?,?,'ASSIGNMENT_RESULT','SUBMISSION',?,10,?)",fm,ft,source,EVENT);
        db.update("INSERT INTO learner_mastery(class_member_id,skill_topic_id,mastery_level,mastery_score) VALUES (?,?,'WEAK',10)",fm,ft);
        mastery.reconcileAssignments(course); assertScore(topic,"70");
        assertThat(db.queryForObject("SELECT count(*) FROM learning_signal WHERE source_id=?",Integer.class,source)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT mastery_score FROM learner_mastery WHERE class_member_id=? AND skill_topic_id=?",BigDecimal.class,fm,ft)).isEqualByComparingTo("10");
    }

    @Test @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void failedMultiPairReconciliationRollsBackEarlierPairsAndRetriesTheWholeClass() {
        UUID other=new TransactionTemplate(transactions).execute(tx->topic(course));
        UUID firstTopic=topic.toString().compareTo(other.toString())<0?topic:other;
        UUID lastTopic=firstTopic.equals(topic)?other:topic;
        new TransactionTemplate(transactions).executeWithoutResult(tx->insert(UUID.randomUUID(),assignment(firstTopic),member,"20",EVENT));
        UUID rejected=new TransactionTemplate(transactions).execute(tx->insert(UUID.randomUUID(),assignment(lastTopic),member,"70",EVENT));
        String name="reject_rebuild_"+rejected.toString().replace("-","");
        db.execute("CREATE FUNCTION "+name+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.source_id='"+rejected+"'::uuid THEN RAISE EXCEPTION 'second pair rejection'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER "+name+" BEFORE INSERT ON learning_signal FOR EACH ROW EXECUTE FUNCTION "+name+"()");
        try {
            assertThatThrownBy(()->mastery.reconcileAssignments(course)).isInstanceOf(RuntimeException.class);
            assertThat(signalCount()).isZero(); assertThat(score(firstTopic)).isNull(); assertThat(score(lastTopic)).isNull();
        } finally { db.execute("DROP TRIGGER "+name+" ON learning_signal");db.execute("DROP FUNCTION "+name+"()"); }
        mastery.reconcileAssignments(course); mastery.reconcileAssignments(course);
        assertScore(firstTopic,"20"); assertScore(lastTopic,"70"); assertThat(signalCount()).isEqualTo(2);
    }

    private List<TopicAssignmentEvidenceReader.TopicAssignmentEvidence> read(){return reader.readLatestGradedCandidates(course,member,topic);}
    private UUID user(String role){ UUID id=UUID.randomUUID();users.add(id);db.update("INSERT INTO lms_user_access(user_id,full_name,email,lms_role) VALUES (?,'Assignment evidence',?,?)",id,id+"@example.test",role);return id; }
    private UUID course(){ UUID id=UUID.randomUUID();courses.add(id);db.update("INSERT INTO class(class_id,tutor_id,class_code,title,enrollment_policy) VALUES (?,?,?,'Course','OPEN')",id,owner,id.toString().substring(0,30));return id; }
    private UUID topic(UUID c){ UUID id=UUID.randomUUID();db.update("INSERT INTO skill_topics(skill_topic_id,class_id,name,difficulty_level) VALUES (?,?,?,1)",id,c,id.toString());return id; }
    private UUID assignment(UUID...topics){
        UUID activity=UUID.randomUUID(), a=UUID.randomUUID();
        db.update("INSERT INTO activity(activity_id,lesson_id,title,activity_type,sequence_order) VALUES (?,?,'Assignment','ASSIGNMENT',0)",activity,lesson);
        db.update("INSERT INTO assignment(assignment_id,activity_id,deadline) VALUES (?,?,?)",a,activity,OffsetDateTime.now().plusDays(1));
        for(UUID t:topics)db.update("INSERT INTO activity_skill_topics(activity_id,skill_topic_id) VALUES (?,?)",activity,t);
        return a;
    }
    private UUID submission(String score,OffsetDateTime time){return insert(UUID.randomUUID(),assignment(topic),member,score,time);}
    private UUID insert(UUID id,UUID a,UUID m,String value,OffsetDateTime time){
        db.update("INSERT INTO submission(submission_id,assignment_id,class_member_id,file_name,storage_path,mime_type,size_bytes,submitted_at,status,score,graded_by,graded_at) VALUES (?,?,?,'answer','unused','text/plain',1,?,'GRADED',?,?,?)",id,a,m,time,new BigDecimal(value),owner,time.plusMinutes(1));return id;
    }
    private UUID ungraded(UUID a,OffsetDateTime time){UUID id=UUID.randomUUID();db.update("INSERT INTO submission(submission_id,assignment_id,class_member_id,file_name,storage_path,mime_type,size_bytes,submitted_at) VALUES (?,?,?,'answer','unused','text/plain',1,?)",id,a,member,time);return id;}
    private void remap(UUID activity,UUID...topics){ClassAccessAdapter.withActor(owner,()->associations.replaceActivityTopics(activity,List.of(topics)));}
    private int signalCount(){return db.queryForObject("SELECT count(*) FROM learning_signal WHERE class_member_id=? AND signal_type='ASSIGNMENT_RESULT'",Integer.class,member);}
    private BigDecimal score(UUID t){return db.query("SELECT mastery_score FROM learner_mastery WHERE class_member_id=? AND skill_topic_id=?",(r,i)->r.getBigDecimal(1),member,t).stream().findFirst().orElse(null);}
    private void assertScore(UUID t,String value){assertThat(score(t)).isEqualByComparingTo(value);}
    private void quizEvidence(String score,int count){
        UUID activity=UUID.randomUUID(), q=UUID.randomUUID(), question=UUID.randomUUID();
        db.update("INSERT INTO activity(activity_id,lesson_id,title,activity_type,sequence_order) VALUES (?,?,'Quiz','QUIZ',0)",activity,lesson);
        db.update("INSERT INTO quiz(quiz_id,activity_id,time_limit_minutes,deadline,attempts_allowed) VALUES (?,?,30,?,100)",q,activity,EVENT.plusDays(1));
        db.update("INSERT INTO question(question_id,quiz_id,question_type,content,points_awarded,order_index) VALUES (?,?,'TRUE_FALSE','Question',100,0)",question,q);
        db.update("INSERT INTO question_skill_topics(question_id,skill_topic_id) VALUES (?,?)",question,topic);
        for(int i=0;i<count;i++){
            UUID attempt=UUID.randomUUID();
            db.update("INSERT INTO quiz_attempt(attempt_id,quiz_id,class_member_id,attempt_number,status,submitted_at,score,graded_at) VALUES (?,?,?,?,'SUBMITTED',?,?,?)",attempt,q,member,i+1,EVENT.plusMinutes(i),new BigDecimal(score),EVENT.plusMinutes(i+1));
            db.update("INSERT INTO attempt_answer(attempt_id,question_id,is_correct,points_earned) VALUES (?,?,false,?)",attempt,question,new BigDecimal(score));
        }
    }
}
