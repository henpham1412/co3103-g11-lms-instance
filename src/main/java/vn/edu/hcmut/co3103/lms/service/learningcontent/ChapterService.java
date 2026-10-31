package vn.edu.hcmut.co3103.lms.service.learningcontent;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.ChapterRequest;
import vn.edu.hcmut.co3103.lms.dto.request.LessonRequest;
import vn.edu.hcmut.co3103.lms.dto.request.MaterialRequest;
import vn.edu.hcmut.co3103.lms.dto.response.ClassOutlineResponse;
import vn.edu.hcmut.co3103.lms.dto.response.ContentItemResponse;
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

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ChapterService {
    private final ClassRepository classRepository;
    private final ClassMemberRepository classMemberRepository;
    private final ChapterRepository chapterRepository;
    private final LessonRepository lessonRepository;
    private final MaterialRepository materialRepository;

    public ChapterService(ClassRepository classRepository, ClassMemberRepository classMemberRepository,
                          ChapterRepository chapterRepository, LessonRepository lessonRepository,
                          MaterialRepository materialRepository) {
        this.classRepository = classRepository;
        this.classMemberRepository = classMemberRepository;
        this.chapterRepository = chapterRepository;
        this.lessonRepository = lessonRepository;
        this.materialRepository = materialRepository;
    }

    @Transactional(readOnly = true)
    public ClassOutlineResponse getOutline(UUID classId, UUID viewerId, boolean tutor) {
        Class target = requireClassAccess(classId, viewerId, tutor);
        List<Chapter> chapters = tutor
                ? chapterRepository.findByClassIdOrderBySequenceOrderAsc(classId)
                : chapterRepository.findByClassIdAndStatusOrderBySequenceOrderAsc(classId, Chapter.VisibilityStatus.PUBLISHED);
        List<ClassOutlineResponse.ChapterItem> items = chapters.stream().map(chapter -> {
            List<Lesson> lessons = tutor
                    ? lessonRepository.findByChapterIdOrderBySequenceOrderAsc(chapter.getChapterId())
                    : lessonRepository.findByChapterIdAndStatusOrderBySequenceOrderAsc(chapter.getChapterId(), Chapter.VisibilityStatus.PUBLISHED);
            List<ClassOutlineResponse.LessonItem> lessonItems = lessons.stream().map(lesson -> {
                List<Material> materials = tutor
                        ? materialRepository.findByLessonIdOrderBySequenceOrderAsc(lesson.getLessonId())
                        : materialRepository.findByLessonIdAndStatusOrderBySequenceOrderAsc(lesson.getLessonId(), Chapter.VisibilityStatus.PUBLISHED);
                return new ClassOutlineResponse.LessonItem(lesson.getLessonId(), lesson.getTitle(), lesson.getDescription(),
                        lesson.getEstimatedDuration(), lesson.getSequenceOrder(), lesson.getStatus(),
                        materials.stream().map(this::toOutlineMaterial).toList());
            }).toList();
            return new ClassOutlineResponse.ChapterItem(chapter.getChapterId(), chapter.getTitle(), chapter.getDescription(),
                    chapter.getSequenceOrder(), chapter.getStatus(), lessonItems);
        }).toList();
        return new ClassOutlineResponse(target.getClassId(), items);
    }

    @Transactional
    public Chapter createChapter(UUID classId, UUID tutorId, ChapterRequest request) {
        Class target = requireClassAccess(classId, tutorId, true);
        ensureClassEditable(target);
        validateTitle(request.title());
        validateOrder(request.sequence_order());
        return chapterRepository.save(new Chapter(classId, request.title().trim(), request.description(),
                request.sequence_order(), request.status()));
    }

    @Transactional(readOnly = true)
    public List<ContentItemResponse.ChapterItem> listChapters(UUID classId, UUID userId, boolean tutor) {
        getOutline(classId, userId, tutor);
        List<Chapter> chapters = tutor
                ? chapterRepository.findByClassIdOrderBySequenceOrderAsc(classId)
                : chapterRepository.findByClassIdAndStatusOrderBySequenceOrderAsc(classId, Chapter.VisibilityStatus.PUBLISHED);
        return chapters.stream().map(ContentItemResponse.ChapterItem::from).toList();
    }

    @Transactional(readOnly = true)
    public ContentItemResponse.ChapterItem getChapter(UUID chapterId, UUID userId, boolean tutor) {
        Chapter chapter = requireChapter(chapterId);
        requireClassAccess(chapter.getClassId(), userId, tutor);
        if (!tutor && chapter.getStatus() != Chapter.VisibilityStatus.PUBLISHED) throw notFound("CHAPTER_NOT_FOUND");
        return ContentItemResponse.ChapterItem.from(chapter);
    }

    @Transactional
    public Chapter updateChapter(UUID chapterId, UUID tutorId, Map<String, Object> patch) {
        Chapter chapter = requireChapter(chapterId);
        Class target = requireClassAccess(chapter.getClassId(), tutorId, true);
        ensureClassEditable(target);
        validatePatch(patch, Set.of("title", "description", "sequence_order", "status"));
        if (patch.containsKey("title")) { validateTitle(text(patch, "title")); chapter.setTitle(text(patch, "title").trim()); }
        if (patch.containsKey("description")) chapter.setDescription(nullableText(patch, "description"));
        if (patch.containsKey("sequence_order")) chapter.setSequenceOrder(nonNegativeInt(patch, "sequence_order"));
        if (patch.containsKey("status")) chapter.setStatus(visibility(patch, "status"));
        return chapterRepository.save(chapter);
    }

    @Transactional
    public void reorderChapters(UUID classId, UUID tutorId, List<UUID> chapterIds) {
        Class target = requireClassAccess(classId, tutorId, true);
        ensureClassEditable(target);
        List<Chapter> chapters = chapterRepository.findByClassIdOrderBySequenceOrderAsc(classId);
        ensureExactOrder(chapters.stream().map(Chapter::getChapterId).toList(), chapterIds);
        var chaptersById = chapters.stream().collect(java.util.stream.Collectors.toMap(Chapter::getChapterId, chapter -> chapter));
        for (int index = 0; index < chapterIds.size(); index++) {
            chaptersById.get(chapterIds.get(index)).setSequenceOrder(index);
        }
        chapterRepository.saveAll(chapters);
    }

    @Transactional
    public void deleteChapter(UUID chapterId, UUID tutorId) {
        Chapter chapter = requireChapter(chapterId);
        Class target = requireClassAccess(chapter.getClassId(), tutorId, true);
        ensureClassEditable(target);
        try {
            chapterRepository.delete(chapter);
            chapterRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw conflict("CONTENT_HAS_DEPENDENT_LEARNING_DATA");
        }
    }

    @Transactional
    public Lesson createLesson(UUID chapterId, UUID tutorId, LessonRequest request) {
        Chapter chapter = requireChapter(chapterId);
        Class target = requireClassAccess(chapter.getClassId(), tutorId, true);
        ensureClassEditable(target);
        validateTitle(request.title());
        validateOrder(request.sequence_order());
        validateDuration(request.estimated_duration());
        return lessonRepository.save(new Lesson(chapterId, request.title().trim(), request.description(),
                request.estimated_duration(), request.sequence_order(), request.status()));
    }

    @Transactional(readOnly = true)
    public List<ContentItemResponse.LessonItem> listLessons(UUID chapterId, UUID userId, boolean tutor) {
        Chapter chapter = requireChapter(chapterId);
        requireClassAccess(chapter.getClassId(), userId, tutor);
        if (!tutor && chapter.getStatus() != Chapter.VisibilityStatus.PUBLISHED) return List.of();
        List<Lesson> lessons = tutor
                ? lessonRepository.findByChapterIdOrderBySequenceOrderAsc(chapterId)
                : lessonRepository.findByChapterIdAndStatusOrderBySequenceOrderAsc(chapterId, Chapter.VisibilityStatus.PUBLISHED);
        return lessons.stream().map(ContentItemResponse.LessonItem::from).toList();
    }

    @Transactional(readOnly = true)
    public ContentItemResponse.LessonItem getLesson(UUID lessonId, UUID userId, boolean tutor) {
        Lesson lesson = requireLesson(lessonId);
        Chapter chapter = requireChapter(lesson.getChapterId());
        requireClassAccess(chapter.getClassId(), userId, tutor);
        if (!tutor && (chapter.getStatus() != Chapter.VisibilityStatus.PUBLISHED || lesson.getStatus() != Chapter.VisibilityStatus.PUBLISHED)) throw notFound("LESSON_NOT_FOUND");
        return ContentItemResponse.LessonItem.from(lesson);
    }

    @Transactional
    public Lesson updateLesson(UUID lessonId, UUID tutorId, Map<String, Object> patch) {
        Lesson lesson = requireLesson(lessonId);
        Chapter chapter = requireChapter(lesson.getChapterId());
        Class target = requireClassAccess(chapter.getClassId(), tutorId, true);
        ensureClassEditable(target);
        validatePatch(patch, Set.of("title", "description", "estimated_duration", "sequence_order", "status"));
        if (patch.containsKey("title")) { validateTitle(text(patch, "title")); lesson.setTitle(text(patch, "title").trim()); }
        if (patch.containsKey("description")) lesson.setDescription(nullableText(patch, "description"));
        if (patch.containsKey("estimated_duration")) { Integer duration = nullableInt(patch, "estimated_duration"); validateDuration(duration); lesson.setEstimatedDuration(duration); }
        if (patch.containsKey("sequence_order")) lesson.setSequenceOrder(nonNegativeInt(patch, "sequence_order"));
        if (patch.containsKey("status")) lesson.setStatus(visibility(patch, "status"));
        return lessonRepository.save(lesson);
    }

    @Transactional
    public void reorderLessons(UUID chapterId, UUID tutorId, List<UUID> lessonIds) {
        Chapter chapter = requireChapter(chapterId);
        Class target = requireClassAccess(chapter.getClassId(), tutorId, true);
        ensureClassEditable(target);
        List<Lesson> lessons = lessonRepository.findByChapterIdOrderBySequenceOrderAsc(chapterId);
        ensureExactOrder(lessons.stream().map(Lesson::getLessonId).toList(), lessonIds);
        var lessonsById = lessons.stream().collect(java.util.stream.Collectors.toMap(Lesson::getLessonId, lesson -> lesson));
        for (int index = 0; index < lessonIds.size(); index++) {
            lessonsById.get(lessonIds.get(index)).setSequenceOrder(index);
        }
        lessonRepository.saveAll(lessons);
    }

    @Transactional
    public void deleteLesson(UUID lessonId, UUID tutorId) {
        Lesson lesson = requireLesson(lessonId);
        Chapter chapter = requireChapter(lesson.getChapterId());
        Class target = requireClassAccess(chapter.getClassId(), tutorId, true);
        ensureClassEditable(target);
        try {
            lessonRepository.delete(lesson);
            lessonRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw conflict("CONTENT_HAS_DEPENDENT_LEARNING_DATA");
        }
    }

    @Transactional
    public Material createMaterial(UUID lessonId, UUID tutorId, MaterialRequest request) {
        Lesson lesson = requireLesson(lessonId);
        Chapter chapter = requireChapter(lesson.getChapterId());
        Class target = requireClassAccess(chapter.getClassId(), tutorId, true);
        ensureClassEditable(target);
        validateTitle(request.title());
        validateOrder(request.sequence_order());
        ensureFileUploadSupported(request.material_type());
        validateMaterialPayload(request.material_type(), request.file_url(), request.content());
        return materialRepository.save(new Material(lessonId, request.title().trim(), request.material_type(),
                request.file_url(), request.content(), request.sequence_order(), request.status()));
    }

    @Transactional(readOnly = true)
    public List<ContentItemResponse.MaterialItem> listMaterials(UUID lessonId, UUID userId, boolean tutor) {
        Lesson lesson = requireLesson(lessonId);
        Chapter chapter = requireChapter(lesson.getChapterId());
        requireClassAccess(chapter.getClassId(), userId, tutor);
        if (!tutor && (chapter.getStatus() != Chapter.VisibilityStatus.PUBLISHED || lesson.getStatus() != Chapter.VisibilityStatus.PUBLISHED)) return List.of();
        List<Material> materials = tutor
                ? materialRepository.findByLessonIdOrderBySequenceOrderAsc(lessonId)
                : materialRepository.findByLessonIdAndStatusOrderBySequenceOrderAsc(lessonId, Chapter.VisibilityStatus.PUBLISHED);
        return materials.stream().map(ContentItemResponse.MaterialItem::from).toList();
    }

    @Transactional(readOnly = true)
    public ContentItemResponse.MaterialItem getMaterial(UUID materialId, UUID userId, boolean tutor) {
        Material material = requireMaterial(materialId);
        Lesson lesson = requireLesson(material.getLessonId());
        Chapter chapter = requireChapter(lesson.getChapterId());
        requireClassAccess(chapter.getClassId(), userId, tutor);
        if (!tutor && (chapter.getStatus() != Chapter.VisibilityStatus.PUBLISHED || lesson.getStatus() != Chapter.VisibilityStatus.PUBLISHED || material.getStatus() != Chapter.VisibilityStatus.PUBLISHED)) throw notFound("MATERIAL_NOT_FOUND");
        return ContentItemResponse.MaterialItem.from(material);
    }

    @Transactional
    public Material updateMaterial(UUID materialId, UUID tutorId, Map<String, Object> patch) {
        Material material = requireMaterial(materialId);
        Lesson lesson = requireLesson(material.getLessonId());
        Chapter chapter = requireChapter(lesson.getChapterId());
        Class target = requireClassAccess(chapter.getClassId(), tutorId, true);
        ensureClassEditable(target);
        validatePatch(patch, Set.of("title", "content", "file_url", "sequence_order", "status"));
        if (patch.containsKey("title")) { validateTitle(text(patch, "title")); material.setTitle(text(patch, "title").trim()); }
        if (patch.containsKey("content")) material.setContent(nullableText(patch, "content"));
        if (patch.containsKey("file_url")) {
            if (material.getMaterialType() != Material.MaterialType.URL) throw badRequest();
            material.setFileUrl(nullableText(patch, "file_url"));
        }
        if (patch.containsKey("sequence_order")) material.setSequenceOrder(nonNegativeInt(patch, "sequence_order"));
        if (patch.containsKey("status")) material.setStatus(visibility(patch, "status"));
        validateMaterialPayload(material.getMaterialType(), material.getFileUrl(), material.getContent());
        return materialRepository.save(material);
    }

    @Transactional
    public void deleteMaterial(UUID materialId, UUID tutorId) {
        Material material = requireMaterial(materialId);
        Lesson lesson = requireLesson(material.getLessonId());
        Chapter chapter = requireChapter(lesson.getChapterId());
        Class target = requireClassAccess(chapter.getClassId(), tutorId, true);
        ensureClassEditable(target);
        try {
            materialRepository.delete(material);
            materialRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw conflict("CONTENT_HAS_DEPENDENT_DATA");
        }
    }

    private ClassOutlineResponse.MaterialItem toOutlineMaterial(Material material) {
        return new ClassOutlineResponse.MaterialItem(material.getMaterialId(), material.getTitle(), material.getMaterialType(), material.getFileUrl(), material.getContent(), material.getSequenceOrder(), material.getStatus(), material.getCreatedAt());
    }
    private Class requireClassAccess(UUID classId, UUID userId, boolean tutor) {
        Class target = classRepository.findById(classId).orElseThrow(() -> notFound("CLASS_NOT_FOUND"));
        if (tutor) {
            if (!target.getTutorId().equals(userId)) throw forbidden();
        } else if (!classMemberRepository.existsByClassIdAndStudentIdAndStatus(classId, userId, ClassMember.MembershipStatus.ACTIVE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "MEMBERSHIP_REQUIRED");
        }
        return target;
    }
    private void ensureClassEditable(Class target) { if (target.getStatus() == Class.ClassStatus.ARCHIVED) throw conflict("CLASS_ARCHIVED"); }
    private Chapter requireChapter(UUID id) { return chapterRepository.findById(id).orElseThrow(() -> notFound("CHAPTER_NOT_FOUND")); }
    private Lesson requireLesson(UUID id) { return lessonRepository.findById(id).orElseThrow(() -> notFound("LESSON_NOT_FOUND")); }
    private Material requireMaterial(UUID id) { return materialRepository.findById(id).orElseThrow(() -> notFound("MATERIAL_NOT_FOUND")); }
    private void validateTitle(String title) { if (title == null || title.isBlank()) throw badRequest(); }
    private void validateOrder(int order) { if (order < 0) throw badRequest(); }
    private void validateDuration(Integer duration) { if (duration != null && (duration < 0 || duration > 1440)) throw badRequest(); }
    private void validateMaterialPayload(Material.MaterialType type, String fileUrl, String content) {
        if (type == null) throw badRequest();
        if (type == Material.MaterialType.TEXT && (content == null || content.isBlank())) throw badRequest();
        if (type != Material.MaterialType.TEXT && (fileUrl == null || fileUrl.isBlank())) throw badRequest();
        if (type == Material.MaterialType.URL) {
            try {
                URI uri = URI.create(fileUrl);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw badRequest();
            }
            catch (IllegalArgumentException exception) { throw badRequest(); }
        }
    }
    private void ensureFileUploadSupported(Material.MaterialType type) {
        if (type == Material.MaterialType.DOCUMENT || type == Material.MaterialType.VIDEO) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "FILE_STORAGE_CONTRACT_NOT_CONFIGURED");
        }
    }
    private void validatePatch(Map<String, Object> patch, Set<String> fields) {
        if (patch == null || patch.isEmpty() || !fields.containsAll(patch.keySet())) throw badRequest();
    }
    private String text(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof String text)) throw badRequest();
        return text;
    }
    private String nullableText(Map<String, Object> values, String field) {
        return values.get(field) == null ? null : text(values, field);
    }
    private int nonNegativeInt(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof Number number)) throw badRequest();
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal) || decimal != Math.rint(decimal)
                || decimal < 0 || decimal > Integer.MAX_VALUE) throw badRequest();
        return number.intValue();
    }
    private Integer nullableInt(Map<String, Object> values, String field) {
        return values.get(field) == null ? null : nonNegativeInt(values, field);
    }
    private Chapter.VisibilityStatus visibility(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof String status)) throw badRequest();
        try { return Chapter.VisibilityStatus.valueOf(status); }
        catch (IllegalArgumentException exception) { throw badRequest(); }
    }
    private void ensureExactOrder(List<UUID> existing, List<UUID> requested) {
        if (requested == null || requested.size() != existing.size() || new HashSet<>(requested).size() != requested.size() || !new HashSet<>(existing).equals(new HashSet<>(requested))) throw badRequest();
    }
    private ResponseStatusException notFound(String code) { return new ResponseStatusException(HttpStatus.NOT_FOUND, code); }
    private ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN"); }
    private ResponseStatusException badRequest() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"); }
    private ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
}
