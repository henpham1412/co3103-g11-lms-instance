package vn.edu.hcmut.co3103.lms.service.progress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.dto.response.AssessmentHistoryItem;
import vn.edu.hcmut.co3103.lms.dto.response.PageResponse;
import vn.edu.hcmut.co3103.lms.dto.response.TutorClassProgressResponse;
import vn.edu.hcmut.co3103.lms.dto.response.TutorMemberProgressResponse;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.CompletionMark;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.CompletionMarkRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TutorAndAssessmentHistoryServiceTest {

    private final ClassRepository classRepository = mock(ClassRepository.class);
    private final ClassMemberRepository classMemberRepository = mock(ClassMemberRepository.class);
    private final LmsUserAccessRepository userAccessRepository = mock(LmsUserAccessRepository.class);
    private final ChapterRepository chapterRepository = mock(ChapterRepository.class);
    private final LessonRepository lessonRepository = mock(LessonRepository.class);
    private final ActivityRepository activityRepository = mock(ActivityRepository.class);
    private final CompletionMarkRepository completionMarkRepository = mock(CompletionMarkRepository.class);
    private final QuizAttemptRepository quizAttemptRepository = mock(QuizAttemptRepository.class);

    private final TutorProgressService tutorService = new TutorProgressService(
            classRepository,
            classMemberRepository,
            userAccessRepository,
            chapterRepository,
            lessonRepository,
            activityRepository,
            completionMarkRepository,
            quizAttemptRepository
    );

    private final AssessmentHistoryService historyService = new AssessmentHistoryService(quizAttemptRepository);

    private final UUID classId = UUID.randomUUID();
    private final UUID tutorId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID chapterId = UUID.randomUUID();
    private final UUID lessonId = UUID.randomUUID();

    private Class ownedClass;
    private ClassMember activeMember;
    private Chapter publishedChapter;
    private Lesson publishedLesson;

    @BeforeEach
    void setUp() {
        ownedClass = new Class(tutorId, "CS101", "Intro", null, null, 30, Class.EnrollmentPolicy.OPEN);
        ReflectionTestUtils.setField(ownedClass, "classId", classId);

        activeMember = new ClassMember(classId, studentId, ClassMember.MembershipStatus.ACTIVE, null, Instant.now());
        ReflectionTestUtils.setField(activeMember, "classMemberId", memberId);

        publishedChapter = new Chapter(classId, "Chapter 1", null, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(publishedChapter, "chapterId", chapterId);

        publishedLesson = new Lesson(chapterId, "Lesson 1", null, 30, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(publishedLesson, "lessonId", lessonId);

        when(classRepository.findById(classId)).thenReturn(Optional.of(ownedClass));
    }

    @Test
    void tutorProgressRejectsNonOwnerAndNonActiveMembers() {
        UUID otherTutor = UUID.randomUUID();
        ResponseStatusException forbiddenClass = assertThrows(
                ResponseStatusException.class,
                () -> tutorService.getClassProgress(classId, otherTutor)
        );
        assertEquals(HttpStatus.FORBIDDEN, forbiddenClass.getStatusCode());
        assertEquals("FORBIDDEN", forbiddenClass.getReason());

        ResponseStatusException forbiddenMember = assertThrows(
                ResponseStatusException.class,
                () -> tutorService.getMemberProgress(classId, memberId, otherTutor)
        );
        assertEquals(HttpStatus.FORBIDDEN, forbiddenMember.getStatusCode());

        // INACTIVE member must not be accessible to tutor member progress
        ClassMember inactive = new ClassMember(classId, studentId, ClassMember.MembershipStatus.INACTIVE, null, null);
        ReflectionTestUtils.setField(inactive, "classMemberId", memberId);
        when(classMemberRepository.findByClassMemberIdAndClassId(memberId, classId)).thenReturn(Optional.of(inactive));

        ResponseStatusException inactiveNotFound = assertThrows(
                ResponseStatusException.class,
                () -> tutorService.getMemberProgress(classId, memberId, tutorId)
        );
        assertEquals(HttpStatus.NOT_FOUND, inactiveNotFound.getStatusCode());
        assertEquals("CLASS_OR_MEMBER_NOT_FOUND", inactiveNotFound.getReason());
    }

    @Test
    void tutorClassProgressReturnsZeroPercentWhenNoEligibleLessonsExist() {
        when(classMemberRepository.findByClassIdAndStatusOrderByJoinedAtAsc(classId, ClassMember.MembershipStatus.ACTIVE))
                .thenReturn(List.of(activeMember));
        when(lessonRepository.findEligibleLessons(classId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of());
        when(completionMarkRepository.findByClassMemberId(memberId)).thenReturn(List.of());
        Instant now = Instant.parse("2026-03-10T09:00:00Z");
        when(userAccessRepository.findById(studentId)).thenReturn(Optional.of(
                new LmsUserAccess(studentId, "Ada Lovelace", "ada@example.test", LmsRole.STUDENT, AccessStatus.ACTIVE, now, now, now)
        ));

        TutorClassProgressResponse response = tutorService.getClassProgress(classId, tutorId);

        assertEquals(1, response.members().size());
        TutorClassProgressResponse.MemberSummary summary = response.members().get(0);
        assertEquals(0, summary.completedLessons());
        assertEquals(0, summary.eligibleLessons());
        assertEquals(0, summary.progressPercentage());
        assertEquals("IN_PROGRESS", summary.status());
    }

    @Test
    void tutorMemberProgressPopulatesLessonQuizStatusesAndOrdersAssessmentOutcomesNewestFirst() {
        when(classMemberRepository.findByClassMemberIdAndClassId(memberId, classId))
                .thenReturn(Optional.of(activeMember));
        when(lessonRepository.findEligibleLessons(classId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(publishedLesson));
        when(chapterRepository.findByClassIdAndStatusOrderBySequenceOrderAsc(classId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(publishedChapter));
        when(lessonRepository.findByChapterIdAndStatusOrderBySequenceOrderAsc(chapterId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(publishedLesson));
        when(completionMarkRepository.findByClassMemberId(memberId))
                .thenReturn(List.of(new CompletionMark(memberId, lessonId, Instant.now())));

        UUID activityId = UUID.randomUUID();
        Activity quizActivity = new Activity(lessonId, "Midterm Quiz", null,
                Activity.ActivityType.QUIZ, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(quizActivity, "activityId", activityId);
        when(activityRepository.findByLessonIdAndStatusOrderBySequenceOrderAsc(lessonId, Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of(quizActivity));

        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setActivity(quizActivity);
        quiz.setTimeLimitMinutes(30);
        quiz.setDeadline(OffsetDateTime.now().plusDays(1));

        OffsetDateTime olderTime = OffsetDateTime.now().minusHours(2);
        OffsetDateTime newerTime = OffsetDateTime.now().minusMinutes(5);

        QuizAttempt attempt1 = new QuizAttempt();
        attempt1.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        attempt1.setQuiz(quiz);
        attempt1.setClassMember(activeMember);
        attempt1.setAttemptNumber(1);
        attempt1.setStatus("SUBMITTED");
        attempt1.setStartedAt(olderTime);
        attempt1.setSubmittedAt(olderTime.plusMinutes(15));
        attempt1.setScore(BigDecimal.valueOf(70));

        QuizAttempt attempt2 = new QuizAttempt();
        attempt2.setId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        attempt2.setQuiz(quiz);
        attempt2.setClassMember(activeMember);
        attempt2.setAttemptNumber(2);
        attempt2.setStatus("IN_PROGRESS");
        attempt2.setStartedAt(newerTime);

        when(quizAttemptRepository.findByClassMember_ClassMemberId(memberId))
                .thenReturn(List.of(attempt1, attempt2));

        TutorMemberProgressResponse response = tutorService.getMemberProgress(classId, memberId, tutorId);

        assertEquals(1, response.completedLessons());
        assertEquals(1, response.eligibleLessons());
        assertEquals(100, response.progressPercentage());
        assertEquals("COMPLETED", response.status());

        var lessonProgress = response.chapters().get(0).lessons().get(0);
        assertEquals(1, lessonProgress.assessmentStatuses().size());
        var quizStatus = lessonProgress.assessmentStatuses().get(0);
        assertEquals(activityId, quizStatus.activityId());
        assertEquals("IN_PROGRESS", quizStatus.status());
        assertEquals(2, quizStatus.attempts().size());

        // Outcomes sorted newest first (attempt2 then attempt1)
        assertEquals(2, response.assessmentOutcomes().size());
        assertEquals(attempt2.getId(), response.assessmentOutcomes().get(0).attemptId());
        assertEquals(attempt1.getId(), response.assessmentOutcomes().get(1).attemptId());
    }

    @Test
    void assessmentHistoryValidatesParametersIsolatesCurrentStudentAndUsesStableDescendingSort() {
        assertThrows(ResponseStatusException.class,
                () -> historyService.getHistory(studentId, null, null, 0, 20));
        assertThrows(ResponseStatusException.class,
                () -> historyService.getHistory(studentId, null, null, 1, 101));
        assertThrows(ResponseStatusException.class,
                () -> historyService.getHistory(studentId, null, "UNKNOWN", 1, 20));

        // ASSIGNMENT type returns empty page without querying quiz attempts
        PageResponse<AssessmentHistoryItem> assignmentPage = historyService.getHistory(
                studentId, classId, "ASSIGNMENT", 1, 20);
        assertTrue(assignmentPage.items().isEmpty());
        verify(quizAttemptRepository, never()).findStudentHistory(any(), any(), any());

        // QUIZ history queries current studentId and sorts by startedAt DESC, id DESC
        when(quizAttemptRepository.findStudentHistory(eq(studentId), eq(classId), any()))
                .thenReturn(new PageImpl<>(List.of()));

        historyService.getHistory(studentId, classId, "QUIZ", 2, 10);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(quizAttemptRepository).findStudentHistory(eq(studentId), eq(classId), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();
        assertEquals(1, pageable.getPageNumber());
        assertEquals(10, pageable.getPageSize());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("startedAt").getDirection());
        assertEquals(Sort.Direction.DESC, pageable.getSort().getOrderFor("id").getDirection());
    }
}
