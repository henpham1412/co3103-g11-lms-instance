package vn.edu.hcmut.co3103.lms.service.learningcontent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.edu.hcmut.co3103.lms.support.MaterialFiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.ContentReport;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.entity.Material;
import vn.edu.hcmut.co3103.lms.dto.request.ActivityRequest;
import vn.edu.hcmut.co3103.lms.dto.request.ChapterRequest;
import vn.edu.hcmut.co3103.lms.dto.request.LessonRequest;
import vn.edu.hcmut.co3103.lms.dto.request.MaterialRequest;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.MaterialRepository;
import vn.edu.hcmut.co3103.lms.repository.moderation.ContentReportRepository;

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
    private final ActivityRepository activityRepository = mock(ActivityRepository.class);
    private final LocalMaterialStorageService materialStorage = mock(LocalMaterialStorageService.class);
    private final ContentReportRepository contentReportRepository = mock(ContentReportRepository.class);
    private final ChapterService service = new ChapterService(classRepository, memberRepository,
            chapterRepository, lessonRepository, materialRepository, activityRepository, materialStorage,
            contentReportRepository);

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
        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.of(target));
        when(chapterRepository.findClassIdByChapterId(any())).thenReturn(Optional.of(classId));
        when(lessonRepository.findClassIdByLessonId(any())).thenReturn(Optional.of(classId));
        when(materialRepository.findClassIdByMaterialId(any())).thenReturn(Optional.of(classId));
        when(activityRepository.findClassIdByActivityId(any())).thenReturn(Optional.of(classId));
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
    void activeStudentCanReadPublishedOutlineWithOrderedNestedContent() {
        Chapter chapter = new Chapter(classId, "Published chapter", null, 0,
                Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        Lesson lesson = new Lesson(chapterId, "Published lesson", null, 25, 0,
                Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        Material material = new Material(lessonId, "Reading", Material.MaterialType.TEXT,
                null, "Lesson text", 0, Chapter.VisibilityStatus.PUBLISHED);
        UUID materialId = UUID.randomUUID();
        ReflectionTestUtils.setField(material, "materialId", materialId);

        when(memberRepository.existsByClassIdAndStudentIdAndStatus(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(true);
        when(chapterRepository.findByClassIdAndStatusOrderBySequenceOrderAsc(classId,
                Chapter.VisibilityStatus.PUBLISHED)).thenReturn(List.of(chapter));
        when(lessonRepository.findByChapterIdAndStatusOrderBySequenceOrderAsc(chapterId,
                Chapter.VisibilityStatus.PUBLISHED)).thenReturn(List.of(lesson));
        when(materialRepository.findByLessonIdAndStatusOrderBySequenceOrderAsc(lessonId,
                Chapter.VisibilityStatus.PUBLISHED)).thenReturn(List.of(material));

        var outline = service.getOutline(classId, studentId, false);

        assertEquals(chapterId, outline.chapters().get(0).chapterId());
        assertEquals(lessonId, outline.chapters().get(0).lessons().get(0).lessonId());
        assertEquals(materialId, outline.chapters().get(0).lessons().get(0).materials().get(0).materialId());
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
    void tutorCanUploadMaterialAndPersistsLocalDownloadUrl() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(
                new Chapter(classId, "Chapter", null, 0, null)));
        when(materialStorage.store(any(), any())).thenReturn(
                new LocalMaterialStorageService.StoredFile("2d8f5627-6550-47db-aae0-3b262cc8e34c.pdf", "application/pdf", 12));
        when(materialRepository.saveAndFlush(any(Material.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Material uploaded = service.createUploadedMaterial(lessonId, tutorId,
                new MaterialRequest("PDF", Material.MaterialType.DOCUMENT, null, null, 0, null),
                new MockMultipartFile("file", "note.pdf", "application/pdf", MaterialFiles.read("valid.pdf")));

        assertEquals(Material.MaterialType.DOCUMENT, uploaded.getMaterialType());
        assertEquals("/api/v1/materials/files/2d8f5627-6550-47db-aae0-3b262cc8e34c.pdf", uploaded.getFileUrl());
        verify(materialRepository).saveAndFlush(any(Material.class));
    }

    @Test
    void uploadByNonOwningTutorIsRejectedBeforeWritingFile() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(new Chapter(classId, "Chapter", null, 0, null)));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
                service.createUploadedMaterial(lessonId, UUID.randomUUID(),
                        new MaterialRequest("PDF", Material.MaterialType.DOCUMENT, null, null, 0, null),
                        new MockMultipartFile("file", "note.pdf", "application/pdf", MaterialFiles.read("valid.pdf"))));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(materialStorage, never()).store(any(), any());
    }

    @Test
    void studentCannotDownloadUploadedMaterialWithoutActiveMembership() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        Chapter chapter = new Chapter(classId, "Chapter", null, 0, null);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        Material material = new Material(lessonId, "PDF", Material.MaterialType.DOCUMENT,
                "/api/v1/materials/files/2d8f5627-6550-47db-aae0-3b262cc8e34c.pdf", null, 0,
                Chapter.VisibilityStatus.PUBLISHED);
        UUID materialId = UUID.randomUUID();
        ReflectionTestUtils.setField(material, "materialId", materialId);
        when(materialRepository.findByFileUrl(material.getFileUrl())).thenReturn(Optional.of(material));
        when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(chapter));
        when(memberRepository.existsByClassIdAndStudentIdAndStatus(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(false);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.getMaterialFile("2d8f5627-6550-47db-aae0-3b262cc8e34c.pdf", studentId, false));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(materialStorage, never()).resolve(any());
    }

    @Test
    void tutorCanCreateChapterAndLessonWithPublishedDefaultsAndParentRelationships() {
        ChapterRequest chapterRequest = new ChapterRequest("  Basics  ", "Intro", 0, null);
        when(chapterRepository.save(any(Chapter.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.empty());

        Chapter chapter = service.createChapter(classId, tutorId, chapterRequest);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        LessonRequest lessonRequest = new LessonRequest("Variables", "Values", 20, 0, null);
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(chapter));
        when(lessonRepository.save(any(Lesson.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Lesson lesson = service.createLesson(chapterId, tutorId, lessonRequest);

        assertEquals("Basics", chapter.getTitle());
        assertEquals(Chapter.VisibilityStatus.PUBLISHED, chapter.getStatus());
        assertEquals(chapterId, lesson.getChapterId());
        assertEquals(Chapter.VisibilityStatus.PUBLISHED, lesson.getStatus());
        verify(chapterRepository).save(any(Chapter.class));
        verify(lessonRepository).save(any(Lesson.class));
    }

    @Test
    void tutorCanCreateTextAndHttpsUrlMaterialsWithOrderedLessonRelationship() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        Chapter chapter = new Chapter(classId, "Chapter", null, 0, null);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(chapter));
        when(materialRepository.save(any(Material.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Material text = service.createMaterial(lessonId, tutorId,
                new MaterialRequest("Notes", Material.MaterialType.TEXT, null, "Read this", 0, null));
        Material url = service.createMaterial(lessonId, tutorId,
                new MaterialRequest("Reference", Material.MaterialType.URL, "https://example.com/guide", null, 1, null));

        assertEquals(lessonId, text.getLessonId());
        assertEquals("Read this", text.getContent());
        assertEquals(Chapter.VisibilityStatus.PUBLISHED, text.getStatus());
        assertEquals(1, url.getSequenceOrder());
        assertEquals("https://example.com/guide", url.getFileUrl());
        verify(materialRepository, org.mockito.Mockito.times(2)).save(any(Material.class));
    }

    @Test
    void studentCannotReadHiddenMaterialOrMaterialUnderHiddenParent() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        Chapter chapter = new Chapter(classId, "Chapter", null, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        Material hiddenMaterial = new Material(lessonId, "Hidden notes", Material.MaterialType.TEXT,
                null, "secret", 0, Chapter.VisibilityStatus.HIDDEN);
        UUID materialId = UUID.randomUUID();
        ReflectionTestUtils.setField(hiddenMaterial, "materialId", materialId);
        when(materialRepository.findById(materialId)).thenReturn(Optional.of(hiddenMaterial));
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(chapter));
        when(memberRepository.existsByClassIdAndStudentIdAndStatus(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(true);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.getMaterial(materialId, studentId, false));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertEquals("MATERIAL_NOT_FOUND", exception.getReason());
    }

    @Test
    void hiddenParentPreventsStudentFromListingNestedContent() {
        Chapter hiddenChapter = new Chapter(classId, "Hidden", null, 0, Chapter.VisibilityStatus.HIDDEN);
        ReflectionTestUtils.setField(hiddenChapter, "chapterId", chapterId);
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(hiddenChapter));
        when(memberRepository.existsByClassIdAndStudentIdAndStatus(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(true);

        assertEquals(List.of(), service.listLessons(chapterId, studentId, false));
        verify(lessonRepository, never()).findByChapterIdAndStatusOrderBySequenceOrderAsc(any(), any());
    }

    @Test
    void reorderChaptersPersistsContiguousOrderInRequestedSequence() {
        Chapter first = new Chapter(classId, "First", null, 7, null);
        Chapter second = new Chapter(classId, "Second", null, 9, null);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        ReflectionTestUtils.setField(first, "chapterId", firstId);
        ReflectionTestUtils.setField(second, "chapterId", secondId);
        when(chapterRepository.findByClassIdOrderBySequenceOrderAsc(classId)).thenReturn(List.of(first, second));

        service.reorderChapters(classId, tutorId, List.of(secondId, firstId));

        assertEquals(0, second.getSequenceOrder());
        assertEquals(1, first.getSequenceOrder());
        verify(chapterRepository).saveAll(List.of(first, second));
    }

    @Test
    void reorderLessonsPersistsContiguousOrderInRequestedSequence() {
        Chapter chapter = new Chapter(classId, "Chapter", null, 0, null);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        Lesson first = new Lesson(chapterId, "First", null, 10, 4, null);
        Lesson second = new Lesson(chapterId, "Second", null, 20, 8, null);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        ReflectionTestUtils.setField(first, "lessonId", firstId);
        ReflectionTestUtils.setField(second, "lessonId", secondId);
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(chapter));
        when(lessonRepository.findByChapterIdOrderBySequenceOrderAsc(chapterId))
                .thenReturn(List.of(first, second));

        service.reorderLessons(chapterId, tutorId, List.of(secondId, firstId));

        assertEquals(0, second.getSequenceOrder());
        assertEquals(1, first.getSequenceOrder());
        verify(lessonRepository).saveAll(List.of(first, second));
    }

    @Test
    void nonOwningTutorCannotEditLessonAndCannotSaveChanges() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(
                new Chapter(classId, "Chapter", null, 0, null)));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.updateLesson(lessonId, UUID.randomUUID(), java.util.Map.of("title", "Changed")));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(lessonRepository, never()).save(any(Lesson.class));
    }

    @Test
    void rejectsInvalidMaterialPayloadsAndNonHttpsUrls() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(
                new Chapter(classId, "Chapter", null, 0, null)));

        ResponseStatusException blankText = assertThrows(ResponseStatusException.class,
                () -> service.createMaterial(lessonId, tutorId,
                        new MaterialRequest("Notes", Material.MaterialType.TEXT, null, "  ", 0, null)));
        ResponseStatusException insecureUrl = assertThrows(ResponseStatusException.class,
                () -> service.createMaterial(lessonId, tutorId,
                        new MaterialRequest("Link", Material.MaterialType.URL, "http://example.com", null, 0, null)));

        assertEquals(HttpStatus.BAD_REQUEST, blankText.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, insecureUrl.getStatusCode());
        verify(materialRepository, never()).save(any(Material.class));
    }

    @Test
    void rejectsChapterAndLessonTitlesLongerThan150Characters() {        Chapter chapter = new Chapter(classId, "Chapter", null, 0, null);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(chapter));
        String tooLongTitle = "x".repeat(151);

        ResponseStatusException chapterException = assertThrows(ResponseStatusException.class,
                () -> service.createChapter(classId, tutorId,
                        new ChapterRequest(tooLongTitle, null, 0, null)));
        ResponseStatusException lessonException = assertThrows(ResponseStatusException.class,
                () -> service.createLesson(chapterId, tutorId,
                        new LessonRequest(tooLongTitle, null, 30, 0, null)));

        assertEquals(HttpStatus.BAD_REQUEST, chapterException.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, lessonException.getStatusCode());
        verify(chapterRepository, never()).save(any(Chapter.class));
        verify(lessonRepository, never()).save(any(Lesson.class));
    }

    @Test
    void contentWritesTakeTheClassLockBeforeCheckingOwnershipOrArchiveState() {
        when(chapterRepository.save(any(Chapter.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createChapter(classId, tutorId, new ChapterRequest("Basics", null, 0, null));

        verify(classRepository).findByIdForUpdate(classId);
        verify(classRepository, never()).findById(classId);
    }

    @Test
    void archivedClassRejectsEveryContentWriteWithConflict() {
        Class archived = new Class(tutorId, "CS101", "Programming", null, null, 20,
                Class.EnrollmentPolicy.OPEN);
        ReflectionTestUtils.setField(archived, "classId", classId);
        archived.setStatus(Class.ClassStatus.ARCHIVED);
        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.of(archived));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.createChapter(classId, tutorId, new ChapterRequest("Basics", null, 0, null)));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("CLASS_ARCHIVED", exception.getReason());
        verify(chapterRepository, never()).save(any(Chapter.class));
    }

    @Test
    void omittedPositionIsAssignedAsTheNextFreeSlot() {
        when(chapterRepository.save(any(Chapter.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(lessonRepository.save(any(Lesson.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(materialRepository.save(any(Material.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chapterRepository.nextSequenceOrderForClass(classId)).thenReturn(3);
        Chapter chapter = new Chapter(classId, "Chapter", null, 0, null);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 4, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(chapter));
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(lessonRepository.nextSequenceOrderForChapter(chapterId)).thenReturn(7);
        when(materialRepository.nextSequenceOrderForLesson(lessonId)).thenReturn(2);

        assertEquals(3, service.createChapter(classId, tutorId,
                new ChapterRequest("Basics", null, null, null)).getSequenceOrder());
        assertEquals(7, service.createLesson(chapterId, tutorId,
                new LessonRequest("Variables", null, 20, null, null)).getSequenceOrder());
        assertEquals(2, service.createMaterial(lessonId, tutorId,
                new MaterialRequest("Notes", Material.MaterialType.TEXT, null, "Read", null, null)).getSequenceOrder());
    }

    @Test
    void aNegativePositionIsRejectedWithoutSavingAnything() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.createChapter(classId, tutorId, new ChapterRequest("Basics", null, -1, null)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(chapterRepository, never()).save(any(Chapter.class));
        verify(chapterRepository, never()).nextSequenceOrderForClass(any());
    }

    @Test
    void tutorCanPlaceAnActivityAndStudentsSeeItOnlyWhenTheWholeChainIsPublished() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        Chapter chapter = new Chapter(classId, "Chapter", null, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        Activity activity = new Activity(lessonId, "Week 1 quiz", null, Activity.ActivityType.QUIZ, 0,
                Chapter.VisibilityStatus.PUBLISHED);
        UUID activityId = UUID.randomUUID();
        ReflectionTestUtils.setField(activity, "activityId", activityId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(chapter));
        when(activityRepository.save(any(Activity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));
        when(memberRepository.existsByClassIdAndStudentIdAndStatus(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(true);

        Activity placed = service.createActivity(lessonId, tutorId,
                activityRequest("{\"title\":\"Week 1 quiz\",\"activity_type\":\"QUIZ\",\"sequence_order\":0}"));

        assertEquals(lessonId, placed.getLessonId());
        assertEquals(Activity.ActivityType.QUIZ, placed.getActivityType());
        assertEquals(Chapter.VisibilityStatus.PUBLISHED, placed.getStatus());
        assertEquals(activityId, service.getActivity(activityId, studentId, false).activityId());

        activity.setStatus(Chapter.VisibilityStatus.HIDDEN);
        ResponseStatusException hidden = assertThrows(ResponseStatusException.class,
                () -> service.getActivity(activityId, studentId, false));
        assertEquals(HttpStatus.NOT_FOUND, hidden.getStatusCode());
        assertEquals("ACTIVITY_NOT_FOUND", hidden.getReason());
    }

    @Test
    void activityPlacementRefusesToCarryAssessmentConfiguration() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(
                new Chapter(classId, "Chapter", null, 0, null)));

        ResponseStatusException mismatch = assertThrows(ResponseStatusException.class,
                () -> service.createActivity(lessonId, tutorId, activityRequest(
                        "{\"title\":\"Quiz\",\"activity_type\":\"QUIZ\",\"quiz\":{\"time_limit\":60}}")));
        ResponseStatusException unknown = assertThrows(ResponseStatusException.class,
                () -> service.createActivity(lessonId, tutorId, activityRequest(
                        "{\"title\":\"Quiz\",\"activity_type\":\"QUIZ\",\"time_limit\":60}")));

        assertEquals(HttpStatus.CONFLICT, mismatch.getStatusCode());
        assertEquals("ASSESSMENT_CONFIGURATION_MISMATCH", mismatch.getReason());
        assertEquals(HttpStatus.BAD_REQUEST, unknown.getStatusCode());
        verify(activityRepository, never()).save(any(Activity.class));
    }

    @Test
    void activityTypeCannotBeReplacedAndAnActivityWithAssessmentRowsCannotBeDeleted() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        Chapter chapter = new Chapter(classId, "Chapter", null, 0, null);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        Activity activity = new Activity(lessonId, "Quiz", null, Activity.ActivityType.QUIZ, 0, null);
        UUID activityId = UUID.randomUUID();
        ReflectionTestUtils.setField(activity, "activityId", activityId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(chapter));
        when(activityRepository.findById(activityId)).thenReturn(Optional.of(activity));

        ResponseStatusException retype = assertThrows(ResponseStatusException.class,
                () -> service.updateActivity(activityId, tutorId,
                        java.util.Map.of("activity_type", "ASSIGNMENT")));
        assertEquals(HttpStatus.BAD_REQUEST, retype.getStatusCode());
        assertEquals(Activity.ActivityType.QUIZ, activity.getActivityType());

        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("quiz rows still reference it"))
                .when(activityRepository).flush();
        ResponseStatusException delete = assertThrows(ResponseStatusException.class,
                () -> service.deleteActivity(activityId, tutorId));
        assertEquals(HttpStatus.CONFLICT, delete.getStatusCode());
        assertEquals("CONTENT_HAS_DEPENDENT_LEARNING_DATA", delete.getReason());
    }

    @Test
    void nonOwningTutorCannotPlaceAnActivity() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(
                new Chapter(classId, "Chapter", null, 0, null)));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.createActivity(lessonId, UUID.randomUUID(), activityRequest(
                        "{\"title\":\"Quiz\",\"activity_type\":\"QUIZ\",\"sequence_order\":0}")));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(activityRepository, never()).save(any(Activity.class));
    }

    @Test
    void outlinePlacesActivitiesAlongsideMaterialsInStoredOrder() {
        Chapter chapter = new Chapter(classId, "Chapter", null, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 25, 0, Chapter.VisibilityStatus.PUBLISHED);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        Activity activity = new Activity(lessonId, "Quiz", null, Activity.ActivityType.QUIZ, 1,
                Chapter.VisibilityStatus.PUBLISHED);
        UUID activityId = UUID.randomUUID();
        ReflectionTestUtils.setField(activity, "activityId", activityId);
        when(memberRepository.existsByClassIdAndStudentIdAndStatus(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(true);
        when(chapterRepository.findByClassIdAndStatusOrderBySequenceOrderAsc(classId,
                Chapter.VisibilityStatus.PUBLISHED)).thenReturn(List.of(chapter));
        when(lessonRepository.findByChapterIdAndStatusOrderBySequenceOrderAsc(chapterId,
                Chapter.VisibilityStatus.PUBLISHED)).thenReturn(List.of(lesson));
        when(activityRepository.findByLessonIdAndStatusOrderBySequenceOrderAsc(lessonId,
                Chapter.VisibilityStatus.PUBLISHED)).thenReturn(List.of(activity));

        var outline = service.getOutline(classId, studentId, false);

        assertEquals(activityId, outline.chapters().get(0).lessons().get(0).activities().get(0).activityId());
        assertEquals(Activity.ActivityType.QUIZ,
                outline.chapters().get(0).lessons().get(0).activities().get(0).activityType());
    }

    @Test
    void aStoredAssetKeyCannotBeSuppliedOrReplacedThroughTheJsonApi() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(
                new Chapter(classId, "Chapter", null, 0, null)));
        String storedUrl = "/api/v1/materials/files/2d8f5627-6550-47db-aae0-3b262cc8e34c.pdf";

        ResponseStatusException create = assertThrows(ResponseStatusException.class,
                () -> service.createMaterial(lessonId, tutorId, new MaterialRequest("Notes",
                        Material.MaterialType.DOCUMENT, storedUrl, null, 0, null)));
        assertEquals(HttpStatus.BAD_REQUEST, create.getStatusCode());

        Material uploaded = new Material(lessonId, "Notes", Material.MaterialType.DOCUMENT, storedUrl,
                null, 0, Chapter.VisibilityStatus.PUBLISHED);
        UUID materialId = UUID.randomUUID();
        ReflectionTestUtils.setField(uploaded, "materialId", materialId);
        when(materialRepository.findById(materialId)).thenReturn(Optional.of(uploaded));

        ResponseStatusException patch = assertThrows(ResponseStatusException.class,
                () -> service.updateMaterial(materialId, tutorId,
                        java.util.Map.of("file_url", "https://example.com/other.pdf")));

        assertEquals(HttpStatus.BAD_REQUEST, patch.getStatusCode());
        assertEquals(storedUrl, uploaded.getFileUrl());
        verify(materialRepository, never()).save(any(Material.class));
    }

    @Test
    void deletingAMaterialOnlyRemovesAnAssetThatNoOtherRowPointsAt() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        Chapter chapter = new Chapter(classId, "Chapter", null, 0, null);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        String storedUrl = "/api/v1/materials/files/2d8f5627-6550-47db-aae0-3b262cc8e34c.pdf";
        Material material = new Material(lessonId, "Notes", Material.MaterialType.DOCUMENT, storedUrl,
                null, 0, Chapter.VisibilityStatus.PUBLISHED);
        UUID materialId = UUID.randomUUID();
        ReflectionTestUtils.setField(material, "materialId", materialId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(chapter));
        when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));

        when(materialRepository.existsByFileUrlAndMaterialIdNot(storedUrl, materialId)).thenReturn(true);
        service.deleteMaterial(materialId, tutorId);
        verify(materialStorage, never()).delete("2d8f5627-6550-47db-aae0-3b262cc8e34c.pdf");

        when(materialRepository.existsByFileUrlAndMaterialIdNot(storedUrl, materialId)).thenReturn(false);
        service.deleteMaterial(materialId, tutorId);
        verify(materialStorage).delete("2d8f5627-6550-47db-aae0-3b262cc8e34c.pdf");
    }

    @Test
    void anUnresolvedReportBlocksMaterialDeletionUntilTheOutcomeIsFinal() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        Chapter chapter = new Chapter(classId, "Chapter", null, 0, null);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        Material material = new Material(lessonId, "Notes", Material.MaterialType.TEXT, null,
                "body", 0, Chapter.VisibilityStatus.PUBLISHED);
        UUID materialId = UUID.randomUUID();
        ReflectionTestUtils.setField(material, "materialId", materialId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(chapter));
        when(materialRepository.findById(materialId)).thenReturn(Optional.of(material));
        when(contentReportRepository.existsByContentTypeAndContentIdAndStatusIn(
                ContentReport.ContentType.MATERIAL, materialId, ContentReport.UNRESOLVED_STATUSES))
                .thenReturn(true);

        ResponseStatusException blocked = assertThrows(ResponseStatusException.class,
                () -> service.deleteMaterial(materialId, tutorId));

        assertEquals(HttpStatus.CONFLICT, blocked.getStatusCode());
        assertEquals("CONTENT_HAS_DEPENDENT_LEARNING_DATA", blocked.getReason());
        verify(materialRepository, never()).delete(any(Material.class));

        when(contentReportRepository.existsByContentTypeAndContentIdAndStatusIn(
                ContentReport.ContentType.MATERIAL, materialId, ContentReport.UNRESOLVED_STATUSES))
                .thenReturn(false);
        service.deleteMaterial(materialId, tutorId);
        verify(materialRepository).delete(material);
    }

    @Test
    void videoMaterialsMustStreamFromAVerifiedHostOrAMediaFile() {
        Lesson lesson = new Lesson(chapterId, "Lesson", null, 30, 0, null);
        ReflectionTestUtils.setField(lesson, "lessonId", lessonId);
        Chapter chapter = new Chapter(classId, "Chapter", null, 0, null);
        ReflectionTestUtils.setField(chapter, "chapterId", chapterId);
        when(lessonRepository.findById(lessonId)).thenReturn(Optional.of(lesson));
        when(chapterRepository.findById(chapterId)).thenReturn(Optional.of(chapter));
        when(materialRepository.save(any(Material.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.createMaterial(lessonId, tutorId, new MaterialRequest("Lecture",
                Material.MaterialType.VIDEO, "https://www.youtube.com/watch?v=abc", null, 0, null));
        service.createMaterial(lessonId, tutorId, new MaterialRequest("Lecture",
                Material.MaterialType.VIDEO, "https://cdn.example.com/lecture.mp4", null, 1, null));

        ResponseStatusException page = assertThrows(ResponseStatusException.class,
                () -> service.createMaterial(lessonId, tutorId, new MaterialRequest("Lecture",
                        Material.MaterialType.VIDEO, "https://example.com/some/page", null, 2, null)));
        ResponseStatusException lookalike = assertThrows(ResponseStatusException.class,
                () -> service.createMaterial(lessonId, tutorId, new MaterialRequest("Lecture",
                        Material.MaterialType.VIDEO, "https://youtube.com.evil.example/watch", null, 3, null)));
        ResponseStatusException insecureDocument = assertThrows(ResponseStatusException.class,
                () -> service.createMaterial(lessonId, tutorId, new MaterialRequest("Notes",
                        Material.MaterialType.DOCUMENT, "http://example.com/notes.pdf", null, 4, null)));

        assertEquals(HttpStatus.BAD_REQUEST, page.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, lookalike.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, insecureDocument.getStatusCode());
        verify(materialRepository, org.mockito.Mockito.times(2)).save(any(Material.class));
    }

    private static ActivityRequest activityRequest(String json) {
        return new tools.jackson.databind.ObjectMapper().readValue(json, ActivityRequest.class);
    }
}
