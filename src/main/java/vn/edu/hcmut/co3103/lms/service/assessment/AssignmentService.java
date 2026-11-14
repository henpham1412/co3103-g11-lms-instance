package vn.edu.hcmut.co3103.lms.service.assessment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.service.learningcontent.ChapterService;
import vn.edu.hcmut.co3103.lms.dto.request.GradeSubmissionRequest;
import vn.edu.hcmut.co3103.lms.dto.response.AssignmentConfigurationResponse;
import vn.edu.hcmut.co3103.lms.dto.response.SubmissionResponse;
import vn.edu.hcmut.co3103.lms.entity.Assignment;
import vn.edu.hcmut.co3103.lms.entity.AssignmentAttachment;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.Submission;
import vn.edu.hcmut.co3103.lms.repository.assessment.AssignmentAttachmentRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.AssignmentRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.SubmissionRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AssignmentService {
    private final AssignmentRepository assignmentRepository;
    private final AssignmentAttachmentRepository attachmentRepository;
    private final SubmissionRepository submissionRepository;
    private final ActivityRepository activityRepository;
    private final ChapterService content;
    private final AssignmentConfigurationService configuration;
    private final ClassMemberRepository memberRepository;
    private final AssignmentFileStorageService storage;

    public AssignmentService(AssignmentRepository assignmentRepository,
                             AssignmentAttachmentRepository attachmentRepository,
                             SubmissionRepository submissionRepository,
                             ActivityRepository activityRepository,
                             ClassMemberRepository memberRepository,
                             AssignmentFileStorageService storage, ChapterService content,
                             AssignmentConfigurationService configuration) {
        this.assignmentRepository = assignmentRepository;
        this.attachmentRepository = attachmentRepository;
        this.submissionRepository = submissionRepository;
        this.activityRepository = activityRepository;
        this.content = content;
        this.configuration = configuration;
        this.memberRepository = memberRepository;
        this.storage = storage;
    }

    @Transactional
    public AssignmentConfigurationResponse patch(UUID activityId, UUID tutorId, Map<String, Object> patch) {
        content.requireWritableActivity(activityId, tutorId);
        Assignment assignment = assignmentRepository.findLockedByActivityId(activityId)
                .orElseThrow(() -> notFound("ASSIGNMENT_NOT_FOUND"));
        configuration.patch(assignment, patch);
        return toConfiguration(assignmentRepository.saveAndFlush(assignment));
    }

    @Transactional(readOnly = true)
    public AssignmentConfigurationResponse getConfiguration(UUID activityId, UUID userId, boolean tutor) {
        content.getActivity(activityId, userId, tutor);
        Assignment assignment = requireAssignmentByActivity(activityId);
        return toConfiguration(assignment);
    }

    @Transactional
    public AssignmentConfigurationResponse addAttachment(UUID assignmentId, UUID tutorId, MultipartFile file) {
        UUID activityId = activityIdForAssignment(assignmentId);
        content.requireWritableActivity(activityId, tutorId);
        Assignment assignment = assignmentRepository.findLockedByAssignmentId(assignmentId).orElseThrow(() -> notFound("ASSIGNMENT_NOT_FOUND"));
        AssignmentFileStorageService.StoredFile stored =
                storage.store(file, null, assignment.getMaxFileSizeMb());
        deleteOnRollback(stored.storagePath());
        AssignmentAttachment attachment = attachmentRepository.save(new AssignmentAttachment(
                assignmentId, stored.fileName(), stored.storagePath(), stored.mimeType(), stored.sizeBytes()));
        return toConfiguration(assignment);
    }

    @Transactional
    public SubmissionResponse submit(UUID assignmentId, UUID studentId, MultipartFile file) {
        UUID activityId = activityIdForAssignment(assignmentId);
        content.requireStudentWritableActivity(activityId, studentId);
        Assignment assignment = assignmentRepository.findLockedByAssignmentId(assignmentId)
                .orElseThrow(() -> notFound("ASSIGNMENT_NOT_FOUND"));
        ClassMember member = requireActiveMember(requireClassId(activityId), studentId);
        OffsetDateTime startedAt = OffsetDateTime.now();
        if (assignment.getOpeningTime() != null && startedAt.isBefore(assignment.getOpeningTime())) {
            throw conflict("SUBMISSION_NOT_ALLOWED");
        }
        boolean late = startedAt.isAfter(assignment.getDeadline());
        if (late && !assignment.isAllowLate()) throw conflict("SUBMISSION_NOT_ALLOWED");

        Submission existing = submissionRepository.findLockedByAssignmentIdAndClassMemberId(
                assignmentId, member.getClassMemberId()).orElse(null);
        if (existing != null && existing.getStatus() == Submission.Status.GRADED) {
            throw conflict("SUBMISSION_ALREADY_GRADED");
        }
        AssignmentFileStorageService.StoredFile stored = storage.store(
                file, assignment.getAllowedFileTypes(), assignment.getMaxFileSizeMb());
        deleteOnRollback(stored.storagePath());
        String oldPath = null;
        if (existing == null) {
            existing = new Submission(assignmentId, member.getClassMemberId(), stored.fileName(),
                    stored.storagePath(), stored.mimeType(), stored.sizeBytes(), startedAt, late);
        } else {
            oldPath = existing.getStoragePath();
            existing.replaceFile(stored.fileName(), stored.storagePath(), stored.mimeType(),
                    stored.sizeBytes(), startedAt, late);
        }
        Submission saved;
        try {
            saved = submissionRepository.saveAndFlush(existing);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("SUBMISSION_ALREADY_EXISTS");
        }
        if (oldPath != null) deleteOnCommit(oldPath);
        return toResponse(saved, member.getStudentId());
    }

    @Transactional(readOnly = true)
    public SubmissionResponse getMine(UUID assignmentId, UUID studentId) {
        Assignment assignment = requireAssignment(assignmentId);
        content.getActivity(assignment.getActivityId(), studentId, false);
        ClassMember member = requireActiveMember(requireClassId(assignment.getActivityId()), studentId);
        Submission submission = submissionRepository.findByAssignmentIdAndClassMemberId(
                assignmentId, member.getClassMemberId()).orElse(null);
        if (submission == null) throw notFound("SUBMISSION_NOT_FOUND");
        return toResponse(submission, member.getStudentId());
    }

    @Transactional(readOnly = true)
    public boolean hasSubmission(UUID assignmentId, UUID studentId) {
        Assignment assignment = requireAssignment(assignmentId);
        content.getActivity(assignment.getActivityId(), studentId, false);
        ClassMember member = requireActiveMember(requireClassId(assignment.getActivityId()), studentId);
        return submissionRepository.findByAssignmentIdAndClassMemberId(
                assignmentId, member.getClassMemberId()).isPresent();
    }

    @Transactional(readOnly = true)
    public Page<SubmissionResponse> list(UUID assignmentId, UUID tutorId, Submission.Status status,
                                         Pageable pageable) {
        Assignment assignment = requireAssignment(assignmentId);
        content.getActivity(assignment.getActivityId(), tutorId, true);
        return submissionRepository.findByAssignmentIdAndStatus(assignmentId, status, pageable)
                .map(submission -> {
                    ClassMember member = memberRepository.findById(submission.getClassMemberId()).orElse(null);
                    return toResponse(submission, member == null ? null : member.getStudentId());
                });
    }

    @Transactional(readOnly = true)
    public SubmissionResponse getSubmission(UUID submissionId, UUID userId, boolean tutor) {
        Submission submission = requireReadableSubmission(submissionId, userId, tutor);
        ClassMember member = memberRepository.findById(submission.getClassMemberId()).orElseThrow(() -> notFound("SUBMISSION_NOT_FOUND"));
        return toResponse(submission, member.getStudentId());
    }

    @Transactional(readOnly = true)
    public DownloadableSubmission download(UUID submissionId, UUID userId, boolean tutor) {
        Submission submission = requireReadableSubmission(submissionId, userId, tutor);
        return new DownloadableSubmission(storage.resolve(submission.getStoragePath()), submission.getMimeType(), submission.getFileName());
    }

    private Submission requireReadableSubmission(UUID submissionId, UUID userId, boolean tutor) {
        Submission submission = submissionRepository.findById(submissionId).orElseThrow(() -> notFound("SUBMISSION_NOT_FOUND"));
        Assignment assignment = requireAssignment(submission.getAssignmentId());
        UUID classId = requireClassId(assignment.getActivityId());
        content.getActivity(assignment.getActivityId(), userId, tutor);
        ClassMember member = memberRepository.findById(submission.getClassMemberId()).orElseThrow(() -> notFound("SUBMISSION_NOT_FOUND"));
        if (!classId.equals(member.getClassId())) throw notFound("SUBMISSION_NOT_FOUND");
        if (!tutor && (!member.getStudentId().equals(userId) || member.getStatus() != ClassMember.MembershipStatus.ACTIVE)) throw forbidden("FORBIDDEN");
        return submission;
    }

    @Transactional(readOnly = true)
    public DownloadableAttachment downloadAttachment(UUID assignmentId, UUID attachmentId, UUID userId, boolean tutor) {
        Assignment assignment = requireAssignment(assignmentId);
        content.getActivity(assignment.getActivityId(), userId, tutor);
        AssignmentAttachment attachment = attachmentRepository.findById(attachmentId)
                .filter(candidate -> assignmentId.equals(candidate.getAssignmentId()))
                .orElseThrow(() -> notFound("ATTACHMENT_NOT_FOUND"));
        return new DownloadableAttachment(storage.resolve(attachment.getStoragePath()), attachment.getMimeType(), attachment.getFileName());
    }

    private void deleteOnRollback(String storagePath) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) storage.delete(storagePath);
            }
        });
    }

    private void deleteOnCommit(String storagePath) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                storage.delete(storagePath);
            }
        });
    }

    @Transactional
    public SubmissionResponse grade(UUID submissionId, UUID tutorId, GradeSubmissionRequest request) {
        UUID activityId = submissionRepository.findActivityIdBySubmissionId(submissionId)
                .orElseThrow(() -> notFound("SUBMISSION_NOT_FOUND"));
        content.requireWritableActivity(activityId, tutorId);
        Submission submission = submissionRepository.findLockedBySubmissionId(submissionId)
                .orElseThrow(() -> notFound("SUBMISSION_NOT_FOUND"));
        if (request == null || request.score() == null
                || request.score().compareTo(BigDecimal.ZERO) < 0
                || request.score().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw badRequest("INVALID_SCORE");
        }
        if (request.version() != null && request.version() != submission.getVersion()) {
            throw conflict("SUBMISSION_STATE_CHANGED");
        }
        submission.grade(request.score(), request.feedback(), tutorId, OffsetDateTime.now());
        ClassMember member = memberRepository.findById(submission.getClassMemberId()).orElse(null);
        return toResponse(submissionRepository.saveAndFlush(submission), member == null ? null : member.getStudentId());
    }

    private Assignment requireAssignmentByActivity(UUID activityId) {
        return assignmentRepository.findByActivityId(activityId)
                .orElseThrow(() -> notFound("ASSIGNMENT_NOT_FOUND"));
    }

    private Assignment requireAssignment(UUID assignmentId) {
        return assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> notFound("ASSIGNMENT_NOT_FOUND"));
    }

    private UUID activityIdForAssignment(UUID assignmentId) {
        return assignmentRepository.findActivityIdByAssignmentId(assignmentId).orElseThrow(() -> notFound("ASSIGNMENT_NOT_FOUND"));
    }

    private UUID requireClassId(UUID activityId) {
        return activityRepository.findClassIdByActivityId(activityId)
                .orElseThrow(() -> notFound("ASSIGNMENT_NOT_FOUND"));
    }

    private ClassMember requireActiveMember(UUID classId, UUID studentId) {
        return memberRepository.findByClassIdAndStudentId(classId, studentId)
                .filter(member -> member.getStatus() == ClassMember.MembershipStatus.ACTIVE)
                .orElseThrow(() -> forbidden("MEMBERSHIP_REQUIRED"));
    }

    private AssignmentConfigurationResponse toConfiguration(Assignment assignment) {
        List<String> types = assignment.getAllowedFileTypes() == null || assignment.getAllowedFileTypes().isBlank()
                ? List.of() : List.of(assignment.getAllowedFileTypes().split(","));
        List<AssignmentConfigurationResponse.Attachment> attachments =
                attachmentRepository.findByAssignmentIdOrderByFileNameAsc(assignment.getAssignmentId()).stream()
                        .map(a -> new AssignmentConfigurationResponse.Attachment(a.getAttachmentId(),
                                a.getFileName(), a.getMimeType(), a.getSizeBytes())).toList();
        return new AssignmentConfigurationResponse(assignment.getAssignmentId(), assignment.getActivityId(),
                assignment.getOpeningTime(), assignment.getDeadline(), assignment.isAllowLate(), types,
                assignment.getMaxFileSizeMb(), attachments);
    }

    private SubmissionResponse toResponse(Submission submission, UUID studentId) {
        return new SubmissionResponse(submission.getSubmissionId(), submission.getAssignmentId(),
                submission.getClassMemberId(), studentId, submission.getFileName(), submission.getMimeType(),
                submission.getSizeBytes(), submission.getSubmittedAt(), submission.isLate(),
                submission.getStatus().name(), submission.getScore(), submission.getFeedback(),
                submission.getGradedBy(), submission.getGradedAt(), submission.getVersion());
    }

    private ResponseStatusException notFound(String code) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, code);
    }

    private ResponseStatusException forbidden(String code) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, code);
    }

    private ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    private ResponseStatusException badRequest(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    public record DownloadableSubmission(Path path, String contentType, String fileName) { }

    public record DownloadableAttachment(Path path, String contentType, String fileName) { }
}
