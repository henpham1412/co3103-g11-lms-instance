package vn.edu.hcmut.co3103.lms.service.adaptive.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.entity.Question;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.LearningResourceScope.ActivityKind;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.LearningResourceScope.ActivityScope;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.LearningResourceScope.QuestionScope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdaptiveIntegrationAdaptersTest {

    private static final UUID CLASS_ID = UUID.randomUUID();
    private static final UUID CHAPTER_ID = UUID.randomUUID();
    private static final UUID LESSON_ID = UUID.randomUUID();
    private static final UUID ACTIVITY_ID = UUID.randomUUID();
    private static final UUID QUIZ_ID = UUID.randomUUID();
    private static final UUID QUESTION_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    private ClassRepository classRepository;
    private LmsUserAccessRepository userAccessRepository;
    private LessonRepository lessonRepository;
    private ChapterRepository chapterRepository;
    private ActivityRepository activityRepository;
    private QuizRepository quizRepository;
    private QuestionRepository questionRepository;

    private ClassAccessAdapter classAccess;
    private LearningResourceScopeAdapter resourceScope;

    @BeforeEach
    void setUp() {
        classRepository = mock(ClassRepository.class);
        userAccessRepository = mock(LmsUserAccessRepository.class);
        lessonRepository = mock(LessonRepository.class);
        chapterRepository = mock(ChapterRepository.class);
        activityRepository = mock(ActivityRepository.class);
        quizRepository = mock(QuizRepository.class);
        questionRepository = mock(QuestionRepository.class);

        classAccess = new ClassAccessAdapter(classRepository, userAccessRepository);
        resourceScope = new LearningResourceScopeAdapter(
                lessonRepository, chapterRepository, activityRepository, quizRepository, questionRepository);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void classAccessPermitsOwningTutorReadOnActiveAndArchivedClasses() {
        authenticateJwt(OWNER_ID);
        when(userAccessRepository.findById(OWNER_ID))
                .thenReturn(Optional.of(user(OWNER_ID, LmsRole.TUTOR, AccessStatus.ACTIVE)));
        when(classRepository.findById(CLASS_ID))
                .thenReturn(Optional.of(course(OWNER_ID, Class.ClassStatus.ACTIVE)));

        assertDoesNotThrow(() -> classAccess.requireReadAccess(CLASS_ID));

        when(classRepository.findById(CLASS_ID))
                .thenReturn(Optional.of(course(OWNER_ID, Class.ClassStatus.ARCHIVED)));
        assertDoesNotThrow(() -> classAccess.requireReadAccess(CLASS_ID));
    }

    @Test
    void classAccessLockForWriteLocksActiveClassAndRejectsArchivedClass() {
        authenticateJwt(OWNER_ID);
        when(userAccessRepository.findById(OWNER_ID))
                .thenReturn(Optional.of(user(OWNER_ID, LmsRole.TUTOR, AccessStatus.ACTIVE)));
        when(classRepository.findByIdForUpdate(CLASS_ID))
                .thenReturn(Optional.of(course(OWNER_ID, Class.ClassStatus.ACTIVE)));

        assertDoesNotThrow(() -> classAccess.lockForWrite(CLASS_ID));
        verify(classRepository).findByIdForUpdate(CLASS_ID);

        when(classRepository.findByIdForUpdate(CLASS_ID))
                .thenReturn(Optional.of(course(OWNER_ID, Class.ClassStatus.ARCHIVED)));
        AppException conflict = assertThrows(AppException.class, () -> classAccess.lockForWrite(CLASS_ID));
        assertEquals(ErrorCode.RESOURCE_STATE_CONFLICT, conflict.getErrorCode());
    }

    @Test
    void classAccessRejectsMissingClassNonOwnerStudentAndSuspendedTutor() {
        authenticateJwt(OWNER_ID);
        when(classRepository.findById(CLASS_ID)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.CLASS_NOT_FOUND,
                assertThrows(AppException.class, () -> classAccess.requireReadAccess(CLASS_ID)).getErrorCode());

        when(classRepository.findById(CLASS_ID))
                .thenReturn(Optional.of(course(OWNER_ID, Class.ClassStatus.ACTIVE)));
        authenticateJwt(OTHER_USER_ID);
        when(userAccessRepository.findById(OTHER_USER_ID))
                .thenReturn(Optional.of(user(OTHER_USER_ID, LmsRole.TUTOR, AccessStatus.ACTIVE)));
        assertEquals(ErrorCode.CLASS_ACCESS_DENIED,
                assertThrows(AppException.class, () -> classAccess.requireReadAccess(CLASS_ID)).getErrorCode());

        when(userAccessRepository.findById(OTHER_USER_ID))
                .thenReturn(Optional.of(user(OTHER_USER_ID, LmsRole.STUDENT, AccessStatus.ACTIVE)));
        assertEquals(ErrorCode.CLASS_ACCESS_DENIED,
                assertThrows(AppException.class, () -> classAccess.requireReadAccess(CLASS_ID)).getErrorCode());

        authenticateJwt(OWNER_ID);
        when(userAccessRepository.findById(OWNER_ID))
                .thenReturn(Optional.of(user(OWNER_ID, LmsRole.TUTOR, AccessStatus.SUSPENDED)));
        assertEquals(ErrorCode.CLASS_ACCESS_DENIED,
                assertThrows(AppException.class, () -> classAccess.requireReadAccess(CLASS_ID)).getErrorCode());
    }

    @Test
    void classAccessSupportsWithActorOverrideWhenSecurityContextIsAbsent() {
        when(userAccessRepository.findById(OWNER_ID))
                .thenReturn(Optional.of(user(OWNER_ID, LmsRole.TUTOR, AccessStatus.ACTIVE)));
        when(classRepository.findByIdForUpdate(CLASS_ID))
                .thenReturn(Optional.of(course(OWNER_ID, Class.ClassStatus.ACTIVE)));

        assertDoesNotThrow(() -> ClassAccessAdapter.withActor(OWNER_ID, () -> classAccess.lockForWrite(CLASS_ID)));
        assertEquals(ErrorCode.CLASS_ACCESS_DENIED,
                assertThrows(AppException.class, () -> classAccess.lockForWrite(CLASS_ID)).getErrorCode());
    }

    @Test
    void learningResourceScopeResolvesLessonActivityQuestionAndQuizQuestionIds() {
        Chapter chapter = new Chapter(CLASS_ID, "Chapter", null, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(chapter, "chapterId", CHAPTER_ID);
        Lesson lesson = new Lesson(CHAPTER_ID, "Lesson", null, null, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(lesson, "lessonId", LESSON_ID);
        Activity activity = new Activity(LESSON_ID, "Quiz Activity", null,
                Activity.ActivityType.QUIZ, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(activity, "activityId", ACTIVITY_ID);
        Quiz quiz = new Quiz();
        quiz.setId(QUIZ_ID);
        quiz.setActivity(activity);
        Question question = new Question();
        question.setId(QUESTION_ID);
        question.setQuiz(quiz);

        when(lessonRepository.findById(LESSON_ID)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(CHAPTER_ID)).thenReturn(Optional.of(chapter));
        when(activityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activity));
        when(quizRepository.findByActivity_ActivityId(ACTIVITY_ID)).thenReturn(Optional.of(quiz));
        when(questionRepository.findById(QUESTION_ID)).thenReturn(Optional.of(question));
        when(questionRepository.findByQuiz_IdOrderByOrderIndex(QUIZ_ID)).thenReturn(List.of(question));

        assertEquals(CLASS_ID, resourceScope.classIdForLesson(LESSON_ID));
        assertEquals(new ActivityScope(CLASS_ID, ActivityKind.QUIZ), resourceScope.activityScope(ACTIVITY_ID));
        assertEquals(new QuestionScope(CLASS_ID, ACTIVITY_ID), resourceScope.questionScope(QUESTION_ID));
        assertEquals(Set.of(QUESTION_ID), resourceScope.questionIdsForQuizActivity(ACTIVITY_ID));
    }

    @Test
    void learningResourceScopeThrowsNotFoundForMissingHierarchyLinks() {
        when(lessonRepository.findById(LESSON_ID)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.LEARNING_RESOURCE_NOT_FOUND,
                assertThrows(AppException.class, () -> resourceScope.classIdForLesson(LESSON_ID)).getErrorCode());

        when(activityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.LEARNING_RESOURCE_NOT_FOUND,
                assertThrows(AppException.class, () -> resourceScope.activityScope(ACTIVITY_ID)).getErrorCode());

        when(questionRepository.findById(QUESTION_ID)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.LEARNING_RESOURCE_NOT_FOUND,
                assertThrows(AppException.class, () -> resourceScope.questionScope(QUESTION_ID)).getErrorCode());
    }

    private void authenticateJwt(UUID subject) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TUTOR"))));
    }

    private LmsUserAccess user(UUID id, LmsRole role, AccessStatus status) {
        return new LmsUserAccess(id, "User", id + "@example.test", role, status, null, null, null);
    }

    private Class course(UUID tutorId, Class.ClassStatus status) {
        Class targetClass = new Class(tutorId, "CS101", "Class", null, null, 20, Class.EnrollmentPolicy.OPEN);
        ReflectionTestUtils.setField(targetClass, "classId", CLASS_ID);
        targetClass.setStatus(status);
        return targetClass;
    }
}
