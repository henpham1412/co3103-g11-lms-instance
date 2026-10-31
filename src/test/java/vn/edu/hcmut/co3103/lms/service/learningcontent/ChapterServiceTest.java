package vn.edu.hcmut.co3103.lms.service.learningcontent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.entity.Material;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.MaterialRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChapterServiceTest {
    private final ClassRepository classRepository = mock(ClassRepository.class);
    private final ClassMemberRepository memberRepository = mock(ClassMemberRepository.class);
    private final ChapterRepository chapterRepository = mock(ChapterRepository.class);
    private final LessonRepository lessonRepository = mock(LessonRepository.class);
    private final MaterialRepository materialRepository = mock(MaterialRepository.class);
    private final ChapterService service = new ChapterService(classRepository, memberRepository,
            chapterRepository, lessonRepository, materialRepository);

    private final UUID classId = UUID.randomUUID();
    private final UUID tutorId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final UUID chapterId = UUID.randomUUID();
    private final UUID lessonId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Class target = new Class(tutorId, "CS101", "Programming", null, null, 20,
                Class.EnrollmentPolicy.OPEN);
        ReflectionTestUtils.setField(target, "classId", classId);
        when(classRepository.findById(classId)).thenReturn(Optional.of(target));
    }

    @Test
    void studentCannotSeeHiddenChapterOrItsChildren() {
        when(memberRepository.existsByClassIdAndStudentIdAndStatus(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(true);
        when(chapterRepository.findByClassIdAndStatusOrderBySequenceOrderAsc(classId,
                Chapter.VisibilityStatus.PUBLISHED)).thenReturn(List.of());

        var outline = service.getOutline(classId, studentId, false);

        assertEquals(List.of(), outline.chapters());
        verify(lessonRepository, never()).findByChapterIdAndStatusOrderBySequenceOrderAsc(
                any(), any());
    }

    @Test
    void studentCannotRetrieveHiddenLessonEvenWithActiveMembership() {
        Chapter hiddenChapter = new Chapter(classId, "Hidden", null, 0,
                Chapter.VisibilityStatus.HIDDEN);
        ReflectionTestUtils.setField(hiddenChapter, "chapterId", chapterId);
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0,
                Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(hiddenChapter));
        when(memberRepository.existsByClassIdAndStudentIdAndStatus(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(true);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.getLesson(lessonId, studentId, false));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertEquals("LESSON_NOT_FOUND", exception.getReason());
    }

    @Test
    void deletingChapterWithDependentLessonsPreservesChildrenAndReturnsConflict() {
        Chapter chapter = new Chapter(classId, "Chapter", null, 0,
                Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(chapter));
        doThrow(new DataIntegrityViolationException("dependent lesson exists"))
                .when(chapterRepository).flush();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.deleteChapter(chapterId, tutorId));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("CONTENT_HAS_DEPENDENT_LEARNING_DATA", exception.getReason());
        verify(chapterRepository).delete(chapter);
        verify(lessonRepository, never()).deleteAll(any());
        verify(materialRepository, never()).deleteAll(any());
    }

    @Test
    void studentWithoutActiveMembershipCannotReadOutline() {
        when(memberRepository.existsByClassIdAndStudentIdAndStatus(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(false);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.getOutline(classId, studentId, false));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals("MEMBERSHIP_REQUIRED", exception.getReason());
    }

    @Test
    void nonOwningTutorCannotCreateChapter() {
        UUID otherTutor = UUID.randomUUID();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.createChapter(classId, otherTutor,
                        new vn.edu.hcmut.co3103.lms.dto.request.ChapterRequest(
                                "Chapter", null, 0, Chapter.VisibilityStatus.PUBLISHED)));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(chapterRepository, never()).save(any(Chapter.class));
    }

    @Test
    void reorderRequiresEveryChapterExactlyOnce() {
        Chapter first = new Chapter(classId, "First", null, 0, null);
        Chapter second = new Chapter(classId, "Second", null, 1, null);
        ReflectionTestUtils.setField(first, "chapterId", chapterId);
        UUID secondId = UUID.randomUUID();
        ReflectionTestUtils.setField(second, "chapterId", secondId);
        when(chapterRepository.findByClassIdOrderBySequenceOrderAsc(classId))
                .thenReturn(List.of(first, second));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.reorderChapters(classId, tutorId, List.of(chapterId, chapterId)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(chapterRepository, never()).saveAll(any());
    }

    @Test
    void documentAndVideoCreationRemainExplicitlyUnavailableWithoutApprovedStorageContract() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(
                new Chapter(classId, "Chapter", null, 0, null)));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.createMaterial(lessonId, tutorId,
                        new vn.edu.hcmut.co3103.lms.dto.request.MaterialRequest(
                                "PDF", Material.MaterialType.DOCUMENT, null, null, 0, null)));

        assertEquals(HttpStatus.NOT_IMPLEMENTED, exception.getStatusCode());
        assertEquals("FILE_STORAGE_CONTRACT_NOT_CONFIGURED", exception.getReason());
        verify(materialRepository, never()).save(any(Material.class));
    }
}
