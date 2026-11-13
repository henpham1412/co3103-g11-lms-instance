package vn.edu.hcmut.co3103.lms.service.interaction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.dto.request.interaction.CreateDiscussionRequest;
import vn.edu.hcmut.co3103.lms.dto.request.interaction.CreateReplyRequest;
import vn.edu.hcmut.co3103.lms.dto.response.DiscussionResponse;
import vn.edu.hcmut.co3103.lms.dto.response.PageResponse;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.Discussion;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.interaction.DiscussionRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Learning-related discussions (DOC-05 section 3.3, DOC-04 section 2.6.1, DOC-01 UC 6.2).
 * Participation is limited to ACTIVE Student members and the owning Tutor; authorship always
 * comes from the authenticated actor, never from the request body.
 */
@Service
public class DiscussionService {

    private static final int TITLE_MAX_LENGTH = 255;
    private static final Set<String> PATCHABLE_FIELDS = Set.of("title", "content");

    private final ClassRepository classRepository;
    private final ClassMemberRepository classMemberRepository;
    private final LessonRepository lessonRepository;
    private final DiscussionRepository discussionRepository;

    public DiscussionService(ClassRepository classRepository,
                             ClassMemberRepository classMemberRepository,
                             LessonRepository lessonRepository,
                             DiscussionRepository discussionRepository) {
        this.classRepository = classRepository;
        this.classMemberRepository = classMemberRepository;
        this.lessonRepository = lessonRepository;
        this.discussionRepository = discussionRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<DiscussionResponse> listThreads(UUID classId, UUID actorId, boolean tutor,
                                                        UUID lessonId, int page, int size) {
        requireReadableClass(classId, actorId, tutor);
        if (lessonId != null) requireLessonOfClass(classId, lessonId);

        long total = discussionRepository.countRootThreads(classId, lessonId);
        long offset = (long) (page - 1) * size;
        List<UUID> rootIds = offset > Integer.MAX_VALUE
                ? List.of()
                : discussionRepository.findRootIdsByRecentActivity(classId, lessonId, size, (int) offset);
        Map<UUID, Discussion> byId = discussionRepository.findAllById(rootIds).stream()
                .collect(Collectors.toMap(Discussion::getDiscussionId, Function.identity()));
        List<DiscussionResponse> items = rootIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(DiscussionResponse::from)
                .toList();
        return new PageResponse<>(items, page, size, total, (int) Math.ceil((double) total / size));
    }

    @Transactional
    public DiscussionResponse createThread(UUID classId, UUID actorId, boolean tutor,
                                           CreateDiscussionRequest request) {
        requireWritableClass(classId, actorId, tutor);
        refuseExtraFields(request.extraFields());
        UUID lessonId = request.lesson_id() == null ? null : requireLessonOfClass(classId, request.lesson_id());
        String title = requireRootTitle(request.title());
        String content = requireContent(request.content());
        Discussion saved = discussionRepository.saveAndFlush(
                new Discussion(classId, lessonId, actorId, null, title, content));
        return DiscussionResponse.from(saved);
    }

    @Transactional
    public DiscussionResponse createReply(UUID parentDiscussionId, UUID actorId, boolean tutor,
                                          CreateReplyRequest request) {
        Discussion parent = requireDiscussion(parentDiscussionId);
        // A reply inherits its parent's class and lesson context, so neither can be supplied.
        requireWritableClass(parent.getClassId(), actorId, tutor);
        refuseExtraFields(request.extraFields());
        String title = optionalTitle(request.title());
        String content = requireContent(request.content());
        Discussion saved = discussionRepository.saveAndFlush(new Discussion(parent.getClassId(),
                parent.getLessonId(), actorId, parent.getDiscussionId(), title, content));
        return DiscussionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public DiscussionResponse getDiscussion(UUID discussionId, UUID actorId, boolean tutor) {
        Discussion discussion = requireDiscussion(discussionId);
        requireReadableClass(discussion.getClassId(), actorId, tutor);
        return DiscussionResponse.from(discussion);
    }

    @Transactional
    public DiscussionResponse updateDiscussion(UUID discussionId, UUID actorId, boolean tutor,
                                               Map<String, Object> patch) {
        Discussion discussion = requireDiscussion(discussionId);
        requireWritableClass(discussion.getClassId(), actorId, tutor);
        requireAuthorOrOwningTutor(discussion, actorId, tutor);
        if (patch == null || patch.isEmpty() || !PATCHABLE_FIELDS.containsAll(patch.keySet())) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        if (patch.containsKey("title")) {
            String title = text(patch.get("title"));
            discussion.setTitle(discussion.getParentId() == null ? requireRootTitle(title) : optionalTitle(title));
        }
        if (patch.containsKey("content")) {
            discussion.setContent(requireContent(text(patch.get("content"))));
        }
        return DiscussionResponse.from(discussionRepository.saveAndFlush(discussion));
    }

    @Transactional
    public void deleteDiscussion(UUID discussionId, UUID actorId, boolean tutor) {
        Discussion discussion = requireDiscussion(discussionId);
        requireWritableClass(discussion.getClassId(), actorId, tutor);
        requireAuthorOrOwningTutor(discussion, actorId, tutor);
        // BR-6.3: physical deletion, and only once no reply depends on this post.
        if (discussionRepository.existsByParentId(discussionId)) {
            throw new AppException(ErrorCode.DISCUSSION_HAS_REPLIES);
        }
        discussionRepository.delete(discussion);
        discussionRepository.flush();
    }

    private Discussion requireDiscussion(UUID discussionId) {
        return discussionRepository.findById(discussionId)
                .orElseThrow(() -> new AppException(ErrorCode.DISCUSSION_NOT_FOUND));
    }

    private Class requireReadableClass(UUID classId, UUID actorId, boolean tutor) {
        Class target = classRepository.findById(classId)
                .orElseThrow(() -> new AppException(ErrorCode.CLASS_NOT_FOUND));
        requireParticipant(target, actorId, tutor);
        return target;
    }

    /**
     * Writes take the same pessimistic Class lock LMS-3/LMS-4 use, so a post cannot be created
     * between an archive transition and the participation check.
     */
    private Class requireWritableClass(UUID classId, UUID actorId, boolean tutor) {
        Class target = classRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new AppException(ErrorCode.CLASS_NOT_FOUND));
        requireParticipant(target, actorId, tutor);
        if (target.getStatus() == Class.ClassStatus.ARCHIVED) {
            throw new AppException(ErrorCode.CLASS_ARCHIVED);
        }
        return target;
    }

    private void requireParticipant(Class target, UUID actorId, boolean tutor) {
        if (tutor) {
            if (!target.getTutorId().equals(actorId)) throw new AppException(ErrorCode.FORBIDDEN);
            return;
        }
        if (!classMemberRepository.existsByClassIdAndStudentIdAndStatus(
                target.getClassId(), actorId, ClassMember.MembershipStatus.ACTIVE)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
    }

    private void requireAuthorOrOwningTutor(Discussion discussion, UUID actorId, boolean tutor) {
        // Ownership was already established by requireWritableClass, so a Tutor here moderates.
        if (!tutor && !discussion.getAuthorId().equals(actorId)) {
            throw new AppException(ErrorCode.FORBIDDEN);
        }
    }

    private UUID requireLessonOfClass(UUID classId, UUID lessonId) {
        UUID lessonClassId = lessonRepository.findClassIdByLessonId(lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.VALIDATION_ERROR));
        if (!classId.equals(lessonClassId)) throw new AppException(ErrorCode.VALIDATION_ERROR);
        return lessonId;
    }

    private void refuseExtraFields(Map<String, Object> extraFields) {
        if (!extraFields.isEmpty()) throw new AppException(ErrorCode.VALIDATION_ERROR);
    }

    private String requireRootTitle(String title) {
        String trimmed = requireText(title);
        if (trimmed.length() > TITLE_MAX_LENGTH) throw new AppException(ErrorCode.VALIDATION_ERROR);
        return trimmed;
    }

    private String optionalTitle(String title) {
        if (title == null) return null;
        String trimmed = requireText(title);
        if (trimmed.length() > TITLE_MAX_LENGTH) throw new AppException(ErrorCode.VALIDATION_ERROR);
        return trimmed;
    }

    private String requireContent(String content) {
        // The body is stored exactly as written; only its emptiness is a validation concern.
        if (content == null || content.isBlank()) throw new AppException(ErrorCode.VALIDATION_ERROR);
        return content;
    }

    private String requireText(String value) {
        if (value == null || value.isBlank()) throw new AppException(ErrorCode.VALIDATION_ERROR);
        return value.trim();
    }

    private String text(Object value) {
        if (!(value instanceof String text)) throw new AppException(ErrorCode.VALIDATION_ERROR);
        return text;
    }
}