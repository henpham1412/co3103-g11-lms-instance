package vn.edu.hcmut.co3103.lms.service.learningcontent;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.ActivityRequest;
import vn.edu.hcmut.co3103.lms.dto.request.ChapterRequest;
import vn.edu.hcmut.co3103.lms.dto.request.LessonRequest;
import vn.edu.hcmut.co3103.lms.dto.request.MaterialRequest;
import vn.edu.hcmut.co3103.lms.dto.response.ClassOutlineResponse;
import vn.edu.hcmut.co3103.lms.dto.response.ContentItemResponse;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.entity.Material;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.MaterialRepository;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ChapterService {
    private static final String LOCAL_FILE_URL_PREFIX = "/api/v1/materials/files/";
    /** Hosts whose embedded player we can trust to stream without probing the resource. */
    private static final Set<String> VERIFIED_VIDEO_DOMAINS = Set.of("youtube.com", "youtube-nocookie.com", "vimeo.com");
    private static final Set<String> VIDEO_STREAM_EXTENSIONS = Set.of("mp4", "webm");

    private final ClassRepository classRepository;
    private final ClassMemberRepository classMemberRepository;
    private final ChapterRepository chapterRepository;
    private final LessonRepository lessonRepository;
    private final MaterialRepository materialRepository;
    private final ActivityRepository activityRepository;
    private final LocalMaterialStorageService materialStorage;

    @Autowired
    public ChapterService(ClassRepository classRepository, ClassMemberRepository classMemberRepository,
                          ChapterRepository chapterRepository, LessonRepository lessonRepository,
                          MaterialRepository materialRepository, ActivityRepository activityRepository,
                          LocalMaterialStorageService materialStorage) {
        this.classRepository = classRepository;
        this.classMemberRepository = classMemberRepository;
        this.chapterRepository = chapterRepository;
        this.lessonRepository = lessonRepository;
        this.materialRepository = materialRepository;
        this.activityRepository = activityRepository;
        this.materialStorage = materialStorage;
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
                List<Activity> activities = tutor
                        ? activityRepository.findByLessonIdOrderBySequenceOrderAsc(lesson.getLessonId())
                        : activityRepository.findByLessonIdAndStatusOrderBySequenceOrderAsc(lesson.getLessonId(), Chapter.VisibilityStatus.PUBLISHED);
                return new ClassOutlineResponse.LessonItem(lesson.getLessonId(), lesson.getTitle(), lesson.getDescription(),
                        lesson.getEstimatedDuration(), lesson.getSequenceOrder(), lesson.getStatus(),
                        materials.stream().map(this::toOutlineMaterial).toList(),
                        activities.stream().map(this::toOutlineActivity).toList());
            }).toList();
            return new ClassOutlineResponse.ChapterItem(chapter.getChapterId(), chapter.getTitle(), chapter.getDescription(),
                    chapter.getSequenceOrder(), chapter.getStatus(), lessonItems);
        }).toList();
        return new ClassOutlineResponse(target.getClassId(), items);
    }

    @Transactional
    public Chapter createChapter(UUID classId, UUID tutorId, ChapterRequest request) {
        requireWritableClass(classId, tutorId);
        validateContentTitle(request.title());
        return chapterRepository.save(new Chapter(classId, request.title().trim(), request.description(),
                resolveOrder(request.sequence_order(), () -> chapterRepository.nextSequenceOrderForClass(classId)),
                request.status()));
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
        requireWritableClass(chapter.getClassId(), tutorId);
        validatePatch(patch, Set.of("title", "description", "sequence_order", "status"));
        if (patch.containsKey("title")) { validateContentTitle(text(patch, "title")); chapter.setTitle(text(patch, "title").trim()); }
        if (patch.containsKey("description")) chapter.setDescription(nullableText(patch, "description"));
        if (patch.containsKey("sequence_order")) chapter.setSequenceOrder(nonNegativeInt(patch, "sequence_order"));
        if (patch.containsKey("status")) chapter.setStatus(visibility(patch, "status"));
        return chapterRepository.save(chapter);
    }

    @Transactional
    public void reorderChapters(UUID classId, UUID tutorId, List<UUID> chapterIds) {
        requireWritableClass(classId, tutorId);
        List<Chapter> chapters = chapterRepository.findByClassIdOrderBySequenceOrderAsc(classId);
        ensureExactOrder(chapters.stream()
                .map(chapter -> Objects.requireNonNull(chapter, "Chapter must not be null").getChapterId())
                .toList(), chapterIds);
        var chaptersById = chapters.stream().collect(java.util.stream.Collectors.toMap(
                chapter -> Objects.requireNonNull(chapter, "Chapter must not be null").getChapterId(),
                chapter -> chapter));
        for (int index = 0; index < chapterIds.size(); index++) {
            chaptersById.get(chapterIds.get(index)).setSequenceOrder(index);
        }
        chapterRepository.saveAll(chapters);
    }

    @Transactional
    public void deleteChapter(UUID chapterId, UUID tutorId) {
        Chapter chapter = requireChapter(chapterId);
        requireWritableClass(chapter.getClassId(), tutorId);
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
        requireWritableClass(chapter.getClassId(), tutorId);
        validateContentTitle(request.title());
        validateDuration(request.estimated_duration());
        return lessonRepository.save(new Lesson(chapterId, request.title().trim(), request.description(),
                request.estimated_duration(),
                resolveOrder(request.sequence_order(), () -> lessonRepository.nextSequenceOrderForChapter(chapterId)),
                request.status()));
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
        if (!tutor && !isVisibleToStudent(chapter, lesson)) throw notFound("LESSON_NOT_FOUND");
        return ContentItemResponse.LessonItem.from(lesson);
    }

    @Transactional
    public Lesson updateLesson(UUID lessonId, UUID tutorId, Map<String, Object> patch) {
        Lesson lesson = requireLesson(lessonId);
        Chapter chapter = requireChapter(lesson.getChapterId());
        requireWritableClass(chapter.getClassId(), tutorId);
        validatePatch(patch, Set.of("title", "description", "estimated_duration", "sequence_order", "status"));
        if (patch.containsKey("title")) { validateContentTitle(text(patch, "title")); lesson.setTitle(text(patch, "title").trim()); }
        if (patch.containsKey("description")) lesson.setDescription(nullableText(patch, "description"));
        if (patch.containsKey("estimated_duration")) { Integer duration = nullableInt(patch, "estimated_duration"); validateDuration(duration); lesson.setEstimatedDuration(duration); }
        if (patch.containsKey("sequence_order")) lesson.setSequenceOrder(nonNegativeInt(patch, "sequence_order"));
        if (patch.containsKey("status")) lesson.setStatus(visibility(patch, "status"));
        return lessonRepository.save(lesson);
    }

    @Transactional
    public void reorderLessons(UUID chapterId, UUID tutorId, List<UUID> lessonIds) {
        Chapter chapter = requireChapter(chapterId);
        requireWritableClass(chapter.getClassId(), tutorId);
        List<Lesson> lessons = lessonRepository.findByChapterIdOrderBySequenceOrderAsc(chapterId);
        ensureExactOrder(lessons.stream()
                .map(lesson -> Objects.requireNonNull(lesson, "Lesson must not be null").getLessonId())
                .toList(), lessonIds);
        var lessonsById = lessons.stream().collect(java.util.stream.Collectors.toMap(
                lesson -> Objects.requireNonNull(lesson, "Lesson must not be null").getLessonId(),
                lesson -> lesson));
        for (int index = 0; index < lessonIds.size(); index++) {
            lessonsById.get(lessonIds.get(index)).setSequenceOrder(index);
        }
        lessonRepository.saveAll(lessons);
    }

    @Transactional
    public void deleteLesson(UUID lessonId, UUID tutorId) {
        Lesson lesson = requireLesson(lessonId);
        Chapter chapter = requireChapter(lesson.getChapterId());
        requireWritableClass(chapter.getClassId(), tutorId);
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
        requireWritableClass(chapter.getClassId(), tutorId);
        validateTitle(request.title());
        // A stored asset key is only minted by the upload endpoint, which validates the bytes.
        if (isLocalFileUrl(request.file_url())) throw badRequest();
        validateMaterialPayload(request.material_type(), request.file_url(), request.content());
        return materialRepository.save(new Material(lessonId, request.title().trim(), request.material_type(),
                request.file_url(), request.content(),
                resolveOrder(request.sequence_order(), () -> materialRepository.nextSequenceOrderForLesson(lessonId)),
                request.status()));
    }

    @Transactional
    public Material createUploadedMaterial(UUID lessonId, UUID tutorId, MaterialRequest request, MultipartFile file) {
        Lesson lesson = requireLesson(lessonId);
        Chapter chapter = requireChapter(lesson.getChapterId());
        requireWritableClass(chapter.getClassId(), tutorId);
        validateTitle(request.title());
        if (request.material_type() != Material.MaterialType.DOCUMENT
                && request.material_type() != Material.MaterialType.VIDEO) throw badRequest();
        if (request.file_url() != null || request.content() != null) throw badRequest();

        LocalMaterialStorageService.StoredFile stored = materialStorage.store(file, request.material_type());
        String fileUrl = LOCAL_FILE_URL_PREFIX + stored.key();
        Material material = new Material(lessonId, request.title().trim(), request.material_type(), fileUrl,
                null,
                resolveOrder(request.sequence_order(), () -> materialRepository.nextSequenceOrderForLesson(lessonId)),
                request.status());
        deleteFileAfterRollback(stored.key());
        try {
            return materialRepository.saveAndFlush(material);
        } catch (RuntimeException exception) {
            materialStorage.delete(stored.key());
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<ContentItemResponse.MaterialItem> listMaterials(UUID lessonId, UUID userId, boolean tutor) {
        Lesson lesson = requireLesson(lessonId);
        Chapter chapter = requireChapter(lesson.getChapterId());
        requireClassAccess(chapter.getClassId(), userId, tutor);
        if (!tutor && !isVisibleToStudent(chapter, lesson)) return List.of();
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
        if (!tutor && (!isVisibleToStudent(chapter, lesson) || material.getStatus() != Chapter.VisibilityStatus.PUBLISHED)) throw notFound("MATERIAL_NOT_FOUND");
        return ContentItemResponse.MaterialItem.from(material);
    }

    @Transactional(readOnly = true)
    public DownloadableMaterial getMaterialFile(String fileKey, UUID userId, boolean tutor) {
        String fileUrl = LOCAL_FILE_URL_PREFIX + fileKey;
        Material material = materialRepository.findByFileUrl(fileUrl)
                .orElseThrow(() -> notFound("FILE_NOT_FOUND"));
        getMaterial(material.getMaterialId(), userId, tutor);
        if (material.getMaterialType() != Material.MaterialType.DOCUMENT
                && material.getMaterialType() != Material.MaterialType.VIDEO) throw notFound("FILE_NOT_FOUND");
        Path path = materialStorage.resolve(fileKey);
        if (!Files.isRegularFile(path)) throw notFound("FILE_NOT_FOUND");
        String extension = fileKey.substring(fileKey.lastIndexOf('.') + 1);
        String mediaType = switch (extension) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            default -> throw notFound("FILE_NOT_FOUND");
        };
        return new DownloadableMaterial(path, mediaType, material.getTitle() + "." + extension);
    }

    @Transactional
    public Material updateMaterial(UUID materialId, UUID tutorId, Map<String, Object> patch) {
        Material material = requireMaterial(materialId);
        Lesson lesson = requireLesson(material.getLessonId());
        Chapter chapter = requireChapter(lesson.getChapterId());
        requireWritableClass(chapter.getClassId(), tutorId);
        validatePatch(patch, Set.of("title", "content", "file_url", "sequence_order", "status"));
        if (patch.containsKey("title")) { validateTitle(text(patch, "title")); material.setTitle(text(patch, "title").trim()); }
        if (patch.containsKey("content")) material.setContent(nullableText(patch, "content"));
        if (patch.containsKey("file_url")) {
            String replacement = nullableText(patch, "file_url");
            // DOCUMENT and VIDEO rows can address an uploaded asset by its stored key. Swapping
            // that key through a JSON patch would orphan the file and skip upload validation, so
            // replacing an asset has to go through delete plus a fresh upload.
            if (isLocalFileUrl(material.getFileUrl()) || isLocalFileUrl(replacement)) throw badRequest();
            if (material.getMaterialType() != Material.MaterialType.URL
                    && material.getMaterialType() != Material.MaterialType.VIDEO) throw badRequest();
            material.setFileUrl(replacement);
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
        requireWritableClass(chapter.getClassId(), tutorId);
        String fileUrl = material.getFileUrl();
        String fileKey = localFileKey(fileUrl);
        try {
            materialRepository.delete(material);
            materialRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw conflict("CONTENT_HAS_DEPENDENT_LEARNING_DATA");
        }
        if (fileKey == null) return;
        // Another Material can point at the same stored asset, so the file may only be removed
        // once the row itself is gone.
        if (!materialRepository.existsByFileUrlAndMaterialIdNot(fileUrl, material.getMaterialId())) {
            deleteFileAfterCommit(fileKey);
        }
    }

    @Transactional
    public Activity createActivity(UUID lessonId, UUID tutorId, ActivityRequest request) {
        Lesson lesson = requireLesson(lessonId);
        Chapter chapter = requireChapter(lesson.getChapterId());
        requireWritableClass(chapter.getClassId(), tutorId);
        if (request.carriesAssessmentConfiguration()) {
            throw conflict("ASSESSMENT_CONFIGURATION_MISMATCH");
        }
        if (!request.extraFields().isEmpty()) throw badRequest();
        validateContentTitle(request.title());
        return activityRepository.save(new Activity(lessonId, request.title().trim(), request.description(),
                request.activity_type(),
                resolveOrder(request.sequence_order(), () -> activityRepository.nextSequenceOrderForLesson(lessonId)),
                request.status()));
    }

    @Transactional(readOnly = true)
    public List<ContentItemResponse.ActivityItem> listActivities(UUID lessonId, UUID userId, boolean tutor) {
        Lesson lesson = requireLesson(lessonId);
        Chapter chapter = requireChapter(lesson.getChapterId());
        requireClassAccess(chapter.getClassId(), userId, tutor);
        if (!tutor && !isVisibleToStudent(chapter, lesson)) return List.of();
        List<Activity> activities = tutor
                ? activityRepository.findByLessonIdOrderBySequenceOrderAsc(lessonId)
                : activityRepository.findByLessonIdAndStatusOrderBySequenceOrderAsc(lessonId, Chapter.VisibilityStatus.PUBLISHED);
        return activities.stream().map(ContentItemResponse.ActivityItem::from).toList();
    }

    @Transactional(readOnly = true)
    public ContentItemResponse.ActivityItem getActivity(UUID activityId, UUID userId, boolean tutor) {
        Activity activity = requireActivity(activityId);
        Lesson lesson = requireLesson(activity.getLessonId());
        Chapter chapter = requireChapter(lesson.getChapterId());
        requireClassAccess(chapter.getClassId(), userId, tutor);
        if (!tutor && (!isVisibleToStudent(chapter, lesson) || activity.getStatus() != Chapter.VisibilityStatus.PUBLISHED)) throw notFound("ACTIVITY_NOT_FOUND");
        return ContentItemResponse.ActivityItem.from(activity);
    }

    @Transactional
    public Activity updateActivity(UUID activityId, UUID tutorId, Map<String, Object> patch) {
        Activity activity = requireActivity(activityId);
        Lesson lesson = requireLesson(activity.getLessonId());
        Chapter chapter = requireChapter(lesson.getChapterId());
        requireWritableClass(chapter.getClassId(), tutorId);
        // activity_type is deliberately not patchable: switching it would leave the Quiz or
        // Assignment configuration owned by the Assessment module bound to the wrong activity.
        validatePatch(patch, Set.of("title", "description", "sequence_order", "status"));
        if (patch.containsKey("title")) { validateContentTitle(text(patch, "title")); activity.setTitle(text(patch, "title").trim()); }
        if (patch.containsKey("description")) activity.setDescription(nullableText(patch, "description"));
        if (patch.containsKey("sequence_order")) activity.setSequenceOrder(nonNegativeInt(patch, "sequence_order"));
        if (patch.containsKey("status")) activity.setStatus(visibility(patch, "status"));
        return activityRepository.save(activity);
    }

    @Transactional
    public void deleteActivity(UUID activityId, UUID tutorId) {
        Activity activity = requireActivity(activityId);
        Lesson lesson = requireLesson(activity.getLessonId());
        Chapter chapter = requireChapter(lesson.getChapterId());
        requireWritableClass(chapter.getClassId(), tutorId);
        try {
            activityRepository.delete(activity);
            activityRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw conflict("CONTENT_HAS_DEPENDENT_LEARNING_DATA");
        }
    }

    private String localFileKey(String fileUrl) {
        return fileUrl != null && fileUrl.startsWith(LOCAL_FILE_URL_PREFIX)
                ? fileUrl.substring(LOCAL_FILE_URL_PREFIX.length()) : null;
    }

    private void deleteFileAfterCommit(String fileKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { materialStorage.delete(fileKey); }
            });
        } else materialStorage.delete(fileKey);
    }

    private void deleteFileAfterRollback(String fileKey) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) materialStorage.delete(fileKey);
                }
            });
        }
    }

    private ClassOutlineResponse.MaterialItem toOutlineMaterial(Material material) {
        return new ClassOutlineResponse.MaterialItem(material.getMaterialId(), material.getTitle(), material.getMaterialType(), material.getFileUrl(), material.getContent(), material.getSequenceOrder(), material.getStatus(), material.getCreatedAt());
    }

    private ClassOutlineResponse.ActivityItem toOutlineActivity(Activity activity) {
        return new ClassOutlineResponse.ActivityItem(activity.getActivityId(), activity.getTitle(), activity.getDescription(),
                activity.getActivityType(), activity.getSequenceOrder(), activity.getStatus(), activity.getCreatedAt());
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

    /**
     * Writes take the same pessimistic Class lock LMS-3 uses, so a content mutation cannot slip
     * between an archive transition and the ownership check, and server-assigned ordering
     * positions are serialised per Class.
     */
    private Class requireWritableClass(UUID classId, UUID tutorId) {
        Class target = classRepository.findByIdForUpdate(classId).orElseThrow(() -> notFound("CLASS_NOT_FOUND"));
        if (!target.getTutorId().equals(tutorId)) throw forbidden();
        ensureClassEditable(target);
        return target;
    }

    private static boolean isVisibleToStudent(Chapter chapter, Lesson lesson) {
        return chapter.getStatus() == Chapter.VisibilityStatus.PUBLISHED
                && lesson.getStatus() == Chapter.VisibilityStatus.PUBLISHED;
    }

    private void ensureClassEditable(Class target) { if (target.getStatus() == Class.ClassStatus.ARCHIVED) throw conflict("CLASS_ARCHIVED"); }
    private Chapter requireChapter(UUID id) { return chapterRepository.findById(id).orElseThrow(() -> notFound("CHAPTER_NOT_FOUND")); }
    private Lesson requireLesson(UUID id) { return lessonRepository.findById(id).orElseThrow(() -> notFound("LESSON_NOT_FOUND")); }
    private Material requireMaterial(UUID id) { return materialRepository.findById(id).orElseThrow(() -> notFound("MATERIAL_NOT_FOUND")); }
    private Activity requireActivity(UUID id) { return activityRepository.findById(id).orElseThrow(() -> notFound("ACTIVITY_NOT_FOUND")); }
    private void validateTitle(String title) { if (title == null || title.isBlank()) throw badRequest(); }
    private void validateContentTitle(String title) {
        validateTitle(title);
        if (title.trim().length() > 150) throw badRequest();
    }
    private void validateDuration(Integer duration) { if (duration != null && (duration < 0 || duration > 1440)) throw badRequest(); }

    /** An omitted position is assigned under the Class lock as the next free slot. */
    private int resolveOrder(Integer requested, IntSupplier nextPosition) {
        if (requested == null) return nextPosition.getAsInt();
        if (requested < 0) throw badRequest();
        return requested;
    }

    private void validateMaterialPayload(Material.MaterialType type, String fileUrl, String content) {
        if (type == null) throw badRequest();
        if (type == Material.MaterialType.TEXT) {
            if (content == null || content.isBlank()) throw badRequest();
            return;
        }
        if (fileUrl == null || fileUrl.isBlank()) throw badRequest();
        if (isLocalFileUrl(fileUrl)) {
            // Only the upload endpoint mints these keys, and it never produces a URL material.
            if (type == Material.MaterialType.URL) throw badRequest();
            return;
        }
        URI uri = parseHttpUrl(fileUrl);
        if (type == Material.MaterialType.VIDEO
                && !isVerifiedVideoHost(uri.getHost())
                && !VIDEO_STREAM_EXTENSIONS.contains(pathExtension(uri))) throw badRequest();
    }

    private URI parseHttpUrl(String fileUrl) {
        URI uri;
        try { uri = URI.create(fileUrl); }
        catch (IllegalArgumentException exception) { throw badRequest(); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw badRequest();
        return uri;
    }

    private boolean isVerifiedVideoHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        return VERIFIED_VIDEO_DOMAINS.stream().anyMatch(domain ->
                normalized.equals(domain) || normalized.endsWith("." + domain));
    }

    private String pathExtension(URI uri) {
        String path = uri.getPath();
        if (path == null) return "";
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash) return "";
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isLocalFileUrl(String fileUrl) { return fileUrl != null && fileUrl.startsWith(LOCAL_FILE_URL_PREFIX); }
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

    public record DownloadableMaterial(Path path, String contentType, String downloadName) { }
}
