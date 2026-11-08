package vn.edu.hcmut.co3103.lms.service.progress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.response.ClassProgressResponse;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.CompletionMark;
import vn.edu.hcmut.co3103.lms.entity.LastVisit;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.CompletionMarkRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LastVisitRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StudentProgressServiceTest {

    private final ClassRepository classRepository = mock(ClassRepository.class);
    private final ClassMemberRepository classMemberRepository = mock(ClassMemberRepository.class);
    private final ChapterRepository chapterRepository = mock(ChapterRepository.class);
    private final LessonRepository lessonRepository = mock(LessonRepository.class);
    private final ActivityRepository activityRepository = mock(ActivityRepository.class);
    private final CompletionMarkRepository completionMarkRepository = mock(CompletionMarkRepository.class);
    private final QuizAttemptRepository quizAttemptRepository = mock(QuizAttemptRepository.class);
    private final LastVisitRepository lastVisitRepository = mock(LastVisitRepository.class);

    private final StudentProgressService service = new StudentProgressService(
            classRepository,
            classMemberRepository,
            chapterRepository,
            lessonRepository,
            activityRepository,
            completionMarkRepository,
            quizAttemptRepository,
            lastVisitRepository
    );

    private final UUID classId = UUID.randomUUID();
    private final UUID tutorId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID chapterId = UUID.randomUUID();
    private final UUID lesson1Id = UUID.randomUUID();
    private final UUID lesson2Id = UUID.randomUUID();

    private Class activeClass;
    private ClassMember activeMember;
    private Chapter publishedChapter;
    private Lesson lesson1;
    private Lesson lesson2;

    @BeforeEach
    void setUp() {
        activeClass = new Class(tutorId, "CS101", "Intro to Java", null, null, 30, Class.EnrollmentPolicy.OPEN);
        ReflectionTestUtils.setField(activeClass, "classId", classId);

        activeMember = new ClassMember(classId, studentId, ClassMember.MembershipStatus.ACTIVE, null, Instant.now());
        ReflectionTestUtils.setField(activeMember, "classMemberId", memberId);

        publishedChapter = new Chapter(classId, "Chapter 1", null, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(publishedChapter, "chapterId", chapterId);

        lesson1 = new Lesson(chapterId, "Lesson 1", null, 30, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(lesson1, "lessonId", lesson1Id);

        lesson2 = new Lesson(chapterId, "Lesson 2", null, 45, 1, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(lesson2, "lessonId", lesson2Id);

        when(classRepository.findById(classId)).thenReturn(Optional.of(activeClass));
        when(classMemberRepository.findByClassIdAndStudentId(classId, studentId)).thenReturn(Optional.of(activeMember));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(publishedChapter));
        when(lessonRepository.findById(lesson1Id)).thenReturn(Optional.of(lesson1));
        when(lessonRepository.findById(lesson2Id)).thenReturn(Optional.of(lesson2));
    }

    @Test
    void inactivePendingAndNonMembersAreRejectedFromProgressAndResumeTarget() {
        ClassMember inactive = new ClassMember(classId, studentId, ClassMember.MembershipStatus.INACTIVE, null, null);
        when(classMemberRepository.findByClassIdAndStudentId(classId, studentId)).thenReturn(Optional.of(inactive));

        ResponseStatusException progressInactive = assertThrows(
                ResponseStatusException.class,
                () -> service.getClassProgress(classId, studentId)
        );
        assertEquals(HttpStatus.FORBIDDEN, progressInactive.getStatusCode());
        assertEquals("MEMBERSHIP_REQUIRED", progressInactive.getReason());

        ResponseStatusException resumeInactive = assertThrows(
                ResponseStatusException.class,
                () -> service.getResumeTarget(classId, studentId)
        );
        assertEquals(HttpStatus.FORBIDDEN, resumeInactive.getStatusCode());
        assertEquals("MEMBERSHIP_REQUIRED", resumeInactive.getReason());

        ClassMember pending = new ClassMember(classId, studentId, ClassMember.MembershipStatus.PENDING, Instant.now(), null);
        when(classMemberRepository.findByClassIdAndStudentId(classId, studentId)).thenReturn(Optional.of(pending));
        assertThrows(ResponseStatusException.class, () -> service.getClassProgress(classId, studentId));
        assertThrows(ResponseStatusException.class, () -> service.getResumeTarget(classId, studentId));

        UUID outsiderId = UUID.randomUUID();
        when(classMemberRepository.findByClassIdAndStudentId(classId, outsiderId)).thenReturn(Optional.empty());
        ResponseStatusException outsiderError = assertThrows(
                ResponseStatusException.class,
                () -> service.getClassProgress(classId, outsiderId)
        );
        assertEquals(HttpStatus.FORBIDDEN, outsiderError.getStatusCode());
        assertEquals("MEMBERSHIP_REQUIRED", outsiderError.getReason());
    }

    @Test
    void activeStudentCanReadProgressAndResumeInArchivedClassWithoutResumingQuizAttempts() {
        activeClass.setStatus(Class.ClassStatus.ARCHIVED);
        when(chapterRepository.findByClassIdAndStatusOrderBySequenceOrderAsc(classId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(publishedChapter));
        when(lessonRepository.findByChapterIdAndStatusOrderBySequenceOrderAsc(chapterId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(lesson1, lesson2));
        when(lessonRepository.findEligibleLessons(classId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(lesson1, lesson2));
        when(completionMarkRepository.findByClassMemberId(memberId))
                .thenReturn(List.of(new CompletionMark(memberId, lesson1Id, Instant.now())));
        when(lastVisitRepository.findById(memberId)).thenReturn(Optional.empty());

        QuizAttempt inProgressAttempt = quizAttempt(UUID.randomUUID(), lesson2Id, "IN_PROGRESS",
                OffsetDateTime.now().minusMinutes(5), 30, OffsetDateTime.now().plusHours(1));
        when(quizAttemptRepository.findByClassMember_ClassMemberId(memberId)).thenReturn(List.of(inProgressAttempt));

        ClassProgressResponse progress = service.getClassProgress(classId, studentId);
        ClassProgressResponse.ResumeTarget resume = service.getResumeTarget(classId, studentId);

        assertEquals(1, progress.completedLessons());
        assertEquals(2, progress.eligibleLessons());
        assertEquals(50, progress.progressPercentage());
        assertEquals("IN_PROGRESS", progress.status());
        assertEquals("LESSON", progress.resumeTarget().targetType());
        assertEquals(lesson2Id, progress.resumeTarget().targetId());
        assertEquals("FIRST_INCOMPLETE_LESSON", progress.resumeTarget().reason());
        assertEquals(progress.resumeTarget(), resume);
    }

    @Test
    void zeroEligibleLessonsReturnsZeroPercentAndInProgressForClassAndChapter() {
        when(chapterRepository.findByClassIdAndStatusOrderBySequenceOrderAsc(classId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(publishedChapter));
        when(lessonRepository.findByChapterIdAndStatusOrderBySequenceOrderAsc(chapterId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of());
        when(lessonRepository.findEligibleLessons(classId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of());
        when(completionMarkRepository.findByClassMemberId(memberId)).thenReturn(List.of());
        when(quizAttemptRepository.findByClassMember_ClassMemberId(memberId)).thenReturn(List.of());
        when(lastVisitRepository.findById(memberId)).thenReturn(Optional.empty());

        ClassProgressResponse progress = service.getClassProgress(classId, studentId);

        assertEquals(0, progress.completedLessons());
        assertEquals(0, progress.eligibleLessons());
        assertEquals(0, progress.progressPercentage());
        assertEquals("IN_PROGRESS", progress.status());
        assertEquals(1, progress.chapters().size());
        assertEquals(0, progress.chapters().get(0).completedLessons());
        assertEquals(0, progress.chapters().get(0).eligibleLessons());
        assertEquals(0, progress.chapters().get(0).progressPercentage());
        assertEquals("CLASS_OUTLINE", progress.resumeTarget().targetType());
        assertEquals(classId, progress.resumeTarget().targetId());
        assertEquals("NO_UNFINISHED_TARGET", progress.resumeTarget().reason());
    }

    @Test
    void resumeTargetPrioritizesUnexpiredInProgressQuizAttemptAndIgnoresExpiredAttempts() {
        when(chapterRepository.findByClassIdAndStatusOrderBySequenceOrderAsc(classId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(publishedChapter));
        when(lessonRepository.findByChapterIdAndStatusOrderBySequenceOrderAsc(chapterId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(lesson1, lesson2));
        when(lessonRepository.findEligibleLessons(classId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(lesson1, lesson2));
        when(completionMarkRepository.findByClassMemberId(memberId)).thenReturn(List.of());
        when(lastVisitRepository.findById(memberId))
                .thenReturn(Optional.of(new LastVisit(memberId, lesson2Id, Instant.now())));

        UUID activeAttemptId = UUID.randomUUID();
        QuizAttempt validAttempt = quizAttempt(activeAttemptId, lesson1Id, "IN_PROGRESS",
                OffsetDateTime.now().minusMinutes(5), 20, OffsetDateTime.now().plusHours(1));
        when(quizAttemptRepository.findByClassMember_ClassMemberId(memberId)).thenReturn(List.of(validAttempt));

        ClassProgressResponse progress = service.getClassProgress(classId, studentId);
        ClassProgressResponse.ResumeTarget resume = service.getResumeTarget(classId, studentId);

        assertEquals("QUIZ_ATTEMPT", resume.targetType());
        assertEquals(activeAttemptId, resume.targetId());
        assertEquals("IN_PROGRESS_ATTEMPT", resume.reason());
        assertEquals(resume, progress.resumeTarget());

        // Now test when the attempt has expired (past time_limit_minutes)
        UUID expiredAttemptId = UUID.randomUUID();
        QuizAttempt expiredAttempt = quizAttempt(expiredAttemptId, lesson1Id, "IN_PROGRESS",
                OffsetDateTime.now().minusMinutes(30), 20, OffsetDateTime.now().plusHours(1));
        when(activityRepository.findByLessonIdAndStatusOrderBySequenceOrderAsc(lesson1Id, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(expiredAttempt.getQuiz().getActivity()));
        when(quizAttemptRepository.findByClassMember_ClassMemberId(memberId)).thenReturn(List.of(expiredAttempt));

        ClassProgressResponse progressAfterExpiry = service.getClassProgress(classId, studentId);
        ClassProgressResponse.ResumeTarget resumeAfterExpiry = service.getResumeTarget(classId, studentId);

        assertEquals("LESSON", resumeAfterExpiry.targetType());
        assertEquals(lesson2Id, resumeAfterExpiry.targetId());
        assertEquals("MOST_RECENTLY_VISITED_LESSON", resumeAfterExpiry.reason());
        assertEquals(resumeAfterExpiry, progressAfterExpiry.resumeTarget());
        assertEquals("SUBMITTED", progressAfterExpiry.chapters().get(0).lessons().get(0)
                .assessmentStatuses().get(0).status());
    }

    @Test
    void resumeTargetSkipsCompletedOrHiddenLastVisitAndReturnsClassOutlineWhenAllLessonsCompleted() {
        when(chapterRepository.findByClassIdAndStatusOrderBySequenceOrderAsc(classId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(publishedChapter));
        when(lessonRepository.findByChapterIdAndStatusOrderBySequenceOrderAsc(chapterId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(lesson1, lesson2));
        when(lessonRepository.findEligibleLessons(classId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(lesson1, lesson2));
        when(quizAttemptRepository.findByClassMember_ClassMemberId(memberId)).thenReturn(List.of());

        // Case 1: LastVisit points to lesson1, which is already completed -> should select lesson2 (FIRST_INCOMPLETE_LESSON)
        when(completionMarkRepository.findByClassMemberId(memberId))
                .thenReturn(List.of(new CompletionMark(memberId, lesson1Id, Instant.now())));
        when(lastVisitRepository.findById(memberId))
                .thenReturn(Optional.of(new LastVisit(memberId, lesson1Id, Instant.now())));

        ClassProgressResponse progress1 = service.getClassProgress(classId, studentId);
        ClassProgressResponse.ResumeTarget resume1 = service.getResumeTarget(classId, studentId);
        assertEquals("LESSON", resume1.targetType());
        assertEquals(lesson2Id, resume1.targetId());
        assertEquals("FIRST_INCOMPLETE_LESSON", resume1.reason());
        assertEquals(resume1, progress1.resumeTarget());
        assertTrue(progress1.chapters().get(0).lessons().get(0).completed());
        assertFalse(progress1.chapters().get(0).lessons().get(1).completed());

        // Case 2: LastVisit points to a HIDDEN lesson -> should select lesson2 (FIRST_INCOMPLETE_LESSON)
        UUID hiddenLessonId = UUID.randomUUID();
        Lesson hiddenLesson = new Lesson(chapterId, "Hidden", null, 10, 2, Chapter.VisibilityStatus.HIDDEN);
        ReflectionTestUtils.setField(hiddenLesson, "lessonId", hiddenLessonId);
        when(lessonRepository.findById(hiddenLessonId)).thenReturn(Optional.of(hiddenLesson));
        when(lastVisitRepository.findById(memberId))
                .thenReturn(Optional.of(new LastVisit(memberId, hiddenLessonId, Instant.now())));

        ClassProgressResponse.ResumeTarget resumeHidden = service.getResumeTarget(classId, studentId);
        assertEquals("LESSON", resumeHidden.targetType());
        assertEquals(lesson2Id, resumeHidden.targetId());
        assertEquals("FIRST_INCOMPLETE_LESSON", resumeHidden.reason());

        // Case 3: All eligible lessons completed (even with LastVisit pointing to lesson2) -> CLASS_OUTLINE
        when(completionMarkRepository.findByClassMemberId(memberId)).thenReturn(List.of(
                new CompletionMark(memberId, lesson1Id, Instant.now()),
                new CompletionMark(memberId, lesson2Id, Instant.now())
        ));
        when(lastVisitRepository.findById(memberId))
                .thenReturn(Optional.of(new LastVisit(memberId, lesson2Id, Instant.now())));

        ClassProgressResponse progressAllDone = service.getClassProgress(classId, studentId);
        ClassProgressResponse.ResumeTarget resumeAllDone = service.getResumeTarget(classId, studentId);
        assertEquals("COMPLETED", progressAllDone.status());
        assertEquals(100, progressAllDone.progressPercentage());
        assertEquals("CLASS_OUTLINE", resumeAllDone.targetType());
        assertEquals(classId, resumeAllDone.targetId());
        assertEquals("NO_UNFINISHED_TARGET", resumeAllDone.reason());
        assertEquals(resumeAllDone, progressAllDone.resumeTarget());
    }

    private QuizAttempt quizAttempt(
            UUID attemptId,
            UUID lessonId,
            String status,
            OffsetDateTime startedAt,
            int timeLimitMinutes,
            OffsetDateTime deadline
    ) {
        Activity activity = new Activity(lessonId, "Quiz Activity", null,
                Activity.ActivityType.QUIZ, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(activity, "activityId", UUID.randomUUID());

        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setActivity(activity);
        quiz.setTimeLimitMinutes(timeLimitMinutes);
        quiz.setDeadline(deadline);

        QuizAttempt attempt = new QuizAttempt();
        attempt.setId(attemptId);
        attempt.setQuiz(quiz);
        attempt.setClassMember(activeMember);
        attempt.setAttemptNumber(1);
        attempt.setStatus(status);
        attempt.setStartedAt(startedAt);
        attempt.setScore(BigDecimal.valueOf(85));
        return attempt;
    }
}
