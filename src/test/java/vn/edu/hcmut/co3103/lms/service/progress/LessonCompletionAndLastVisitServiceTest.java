package vn.edu.hcmut.co3103.lms.service.progress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.response.CompletionMarkResponse;
import vn.edu.hcmut.co3103.lms.dto.response.LastVisitResponse;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.CompletionMark;
import vn.edu.hcmut.co3103.lms.entity.LastVisit;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.CompletionMarkRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LastVisitRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LessonCompletionAndLastVisitServiceTest {

    private final ClassRepository classRepository = mock(ClassRepository.class);
    private final ClassMemberRepository classMemberRepository = mock(ClassMemberRepository.class);
    private final ChapterRepository chapterRepository = mock(ChapterRepository.class);
    private final LessonRepository lessonRepository = mock(LessonRepository.class);
    private final CompletionMarkRepository completionMarkRepository = mock(CompletionMarkRepository.class);
    private final LastVisitRepository lastVisitRepository = mock(LastVisitRepository.class);

    private final LessonCompletionService completionService = new LessonCompletionService(
            classRepository,
            classMemberRepository,
            chapterRepository,
            lessonRepository,
            completionMarkRepository,
            mock(vn.edu.hcmut.co3103.lms.service.adaptive.MasteryRecalculationService.class)
    );

    private final LastVisitService lastVisitService = new LastVisitService(
            classRepository,
            classMemberRepository,
            chapterRepository,
            lessonRepository,
            lastVisitRepository
    );

    private final UUID classId = UUID.randomUUID();
    private final UUID tutorId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID chapterId = UUID.randomUUID();
    private final UUID lessonId = UUID.randomUUID();

    private Class activeClass;
    private ClassMember activeMember;
    private Chapter publishedChapter;
    private Lesson publishedLesson;

    @BeforeEach
    void setUp() {
        activeClass = new Class(tutorId, "CS101", "Intro", null, null, 30, Class.EnrollmentPolicy.OPEN);
        ReflectionTestUtils.setField(activeClass, "classId", classId);

        activeMember = new ClassMember(classId, studentId, ClassMember.MembershipStatus.ACTIVE, null, Instant.now());
        ReflectionTestUtils.setField(activeMember, "classMemberId", memberId);

        publishedChapter = new Chapter(classId, "Chapter 1", null, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(publishedChapter, "chapterId", chapterId);

        publishedLesson = new Lesson(chapterId, "Lesson 1", null, 30, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(publishedLesson, "lessonId", lessonId);

        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.of(activeClass));
        when(classMemberRepository.findLockedByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.of(activeMember));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(publishedChapter));
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(publishedLesson));
        when(completionMarkRepository.save(any(CompletionMark.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(lastVisitRepository.save(any(LastVisit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void markCompleteCreatesMarkAndPreservesOriginalTimestampOnRepeatedPut() {
        Instant initialTimestamp = Instant.parse("2026-10-01T10:15:30Z");
        CompletionMark existing = new CompletionMark(memberId, lessonId, initialTimestamp);

        when(completionMarkRepository.findByClassMemberIdAndLessonId(memberId, lessonId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));

        CompletionMarkResponse created = completionService.markComplete(classId, lessonId, studentId);
        assertTrue(created.completed());
        assertNotNull(created.markedAt());
        verify(completionMarkRepository).save(any(CompletionMark.class));

        CompletionMarkResponse repeated = completionService.markComplete(classId, lessonId, studentId);
        assertTrue(repeated.completed());
        assertEquals(initialTimestamp, repeated.markedAt());
        assertEquals(initialTimestamp, existing.getMarkedAt());
        // save() must not be called a second time to overwrite markedAt
        verify(completionMarkRepository).save(any(CompletionMark.class));
    }

    @Test
    void markIncompleteDeletesExistingMarkAndIsIdempotentWhenAbsent() {
        CompletionMark existing = new CompletionMark(memberId, lessonId, Instant.now());
        when(completionMarkRepository.findByClassMemberIdAndLessonId(memberId, lessonId))
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.empty());

        CompletionMarkResponse deleted = completionService.markIncomplete(classId, lessonId, studentId);
        assertFalse(deleted.completed());
        assertNull(deleted.markedAt());
        verify(completionMarkRepository).delete(existing);

        CompletionMarkResponse idempotent = completionService.markIncomplete(classId, lessonId, studentId);
        assertFalse(idempotent.completed());
        assertNull(idempotent.markedAt());
    }

    @Test
    void completionAndLastVisitEnforceArchiveMembershipClassScopeAndVisibilityRules() {
        // 1. Archived class -> 409 CLASS_ARCHIVED
        activeClass.setStatus(Class.ClassStatus.ARCHIVED);
        ResponseStatusException archivedCompletion = assertThrows(
                ResponseStatusException.class,
                () -> completionService.markComplete(classId, lessonId, studentId)
        );
        assertEquals(HttpStatus.CONFLICT, archivedCompletion.getStatusCode());
        assertEquals("CLASS_ARCHIVED", archivedCompletion.getReason());

        ResponseStatusException archivedVisit = assertThrows(
                ResponseStatusException.class,
                () -> lastVisitService.recordVisit(classId, lessonId, studentId)
        );
        assertEquals(HttpStatus.CONFLICT, archivedVisit.getStatusCode());
        assertEquals("CLASS_ARCHIVED", archivedVisit.getReason());
        activeClass.setStatus(Class.ClassStatus.ACTIVE);

        // 2. INACTIVE member -> 403 MEMBERSHIP_REQUIRED
        activeMember.setStatus(ClassMember.MembershipStatus.INACTIVE);
        ResponseStatusException inactiveError = assertThrows(
                ResponseStatusException.class,
                () -> completionService.markComplete(classId, lessonId, studentId)
        );
        assertEquals(HttpStatus.FORBIDDEN, inactiveError.getStatusCode());
        assertEquals("MEMBERSHIP_REQUIRED", inactiveError.getReason());

        ResponseStatusException inactiveVisitError = assertThrows(
                ResponseStatusException.class,
                () -> lastVisitService.recordVisit(classId, lessonId, studentId)
        );
        assertEquals(HttpStatus.FORBIDDEN, inactiveVisitError.getStatusCode());
        assertEquals("MEMBERSHIP_REQUIRED", inactiveVisitError.getReason());
        activeMember.setStatus(ClassMember.MembershipStatus.ACTIVE);

        // 3. Hidden lesson -> 409 LESSON_NOT_ACCESSIBLE
        publishedLesson.setStatus(Chapter.VisibilityStatus.HIDDEN);
        ResponseStatusException hiddenLessonError = assertThrows(
                ResponseStatusException.class,
                () -> completionService.markComplete(classId, lessonId, studentId)
        );
        assertEquals(HttpStatus.CONFLICT, hiddenLessonError.getStatusCode());
        assertEquals("LESSON_NOT_ACCESSIBLE", hiddenLessonError.getReason());

        ResponseStatusException hiddenVisitError = assertThrows(
                ResponseStatusException.class,
                () -> lastVisitService.recordVisit(classId, lessonId, studentId)
        );
        assertEquals(HttpStatus.CONFLICT, hiddenVisitError.getStatusCode());
        assertEquals("LESSON_NOT_ACCESSIBLE", hiddenVisitError.getReason());
        publishedLesson.setStatus(Chapter.VisibilityStatus.PUBLISHED);

        // 4. Lesson belonging to another class -> 404 LESSON_NOT_FOUND
        Chapter otherClassChapter = new Chapter(UUID.randomUUID(), "Other", null, 0, Chapter.VisibilityStatus.PUBLISHED);
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(otherClassChapter));
        ResponseStatusException wrongClassError = assertThrows(
                ResponseStatusException.class,
                () -> lastVisitService.recordVisit(classId, lessonId, studentId)
        );
        assertEquals(HttpStatus.NOT_FOUND, wrongClassError.getStatusCode());
        assertEquals("LESSON_NOT_FOUND", wrongClassError.getReason());
        verify(lastVisitRepository, never()).save(any());
    }

    @Test
    void lastVisitUpsertsAndRefreshesVisitedAtTimestamp() {
        Instant oldVisitedAt = Instant.parse("2026-10-01T08:00:00Z");
        LastVisit existing = new LastVisit(memberId, UUID.randomUUID(), oldVisitedAt);
        when(lastVisitRepository.findById(memberId)).thenReturn(Optional.of(existing));

        LastVisitResponse response = lastVisitService.recordVisit(classId, lessonId, studentId);

        assertEquals(lessonId, response.lessonId());
        assertTrue(response.visitedAt().isAfter(oldVisitedAt));
        verify(lastVisitRepository).save(existing);
    }
}
