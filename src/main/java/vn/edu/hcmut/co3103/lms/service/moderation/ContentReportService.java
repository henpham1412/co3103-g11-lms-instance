package vn.edu.hcmut.co3103.lms.service.moderation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.moderation.CreateContentReportRequest;
import vn.edu.hcmut.co3103.lms.dto.response.AdminContentReportResponse;
import vn.edu.hcmut.co3103.lms.dto.response.ContentReportResponse;
import vn.edu.hcmut.co3103.lms.dto.response.PageResponse;
import vn.edu.hcmut.co3103.lms.entity.ContentReport;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.repository.interaction.DiscussionRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.MaterialRepository;
import vn.edu.hcmut.co3103.lms.repository.moderation.ContentReportRepository;
import vn.edu.hcmut.co3103.lms.service.interaction.DiscussionService;
import vn.edu.hcmut.co3103.lms.service.learningcontent.ChapterService;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.List;
import java.util.HashSet;
import jakarta.persistence.EntityManager;
import vn.edu.hcmut.co3103.lms.domain.AuditLog;
import vn.edu.hcmut.co3103.lms.repository.AuditLogRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;

/**
 * Content reporting and moderation (DOC-05 sections 3.5 and 5.3, DOC-04 section 2.5.2, BR-6.5).
 * The reporter always comes from the authenticated actor, and only an Administrator may read or
 * review a report. Reporting never hides the target.
 */
@Service
public class ContentReportService {

    private static final Set<String> PATCHABLE_FIELDS = Set.of("status");

    private final ContentReportRepository contentReportRepository;
    private final DiscussionRepository discussionRepository;
    private final MaterialRepository materialRepository;
    private final DiscussionService discussionService;
    private final ChapterService chapterService;
    private final ClassRepository classRepository;
    private final AuditLogRepository auditLogs;
    private final EntityManager entityManager;
    private final ModerationTargetReader targetReader;

    public ContentReportService(ContentReportRepository contentReportRepository,
                                DiscussionRepository discussionRepository,
                                MaterialRepository materialRepository,
                                DiscussionService discussionService,
                                ChapterService chapterService, ClassRepository classRepository,
                                AuditLogRepository auditLogs, EntityManager entityManager,
                                ModerationTargetReader targetReader) {
        this.contentReportRepository = contentReportRepository;
        this.discussionRepository = discussionRepository;
        this.materialRepository = materialRepository;
        this.discussionService = discussionService;
        this.chapterService = chapterService;
        this.classRepository = classRepository;
        this.auditLogs = auditLogs;
        this.entityManager = entityManager;
        this.targetReader = targetReader;
    }

    @Transactional
    public ContentReportResponse create(UUID reporterId, boolean tutor, CreateContentReportRequest request) {
        if (!request.extraFields().isEmpty()) throw new AppException(ErrorCode.VALIDATION_ERROR);
        ContentReport.ContentType contentType = contentType(request.content_type());
        ContentReport.Reason reason = reason(request.reason());
        String description = optionalDescription(request.description());
        UUID contentId = request.content_id();
        UUID classId = (contentType == ContentReport.ContentType.DISCUSSION
                ? discussionRepository.findClassIdByDiscussionId(contentId)
                : materialRepository.findClassIdByMaterialId(contentId))
                .orElseThrow(() -> new AppException(ErrorCode.REPORTED_CONTENT_NOT_FOUND));
        // Creation and deletion serialize on Class before any target mutation. Reporting is a
        // moderation operation: retain canonical read rules, including archived read access.
        var lockedClass = classRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new AppException(ErrorCode.REPORTED_CONTENT_NOT_FOUND));
        entityManager.refresh(lockedClass);
        // Refresh only the relevant target; do not clear an enclosing transaction's context.
        try {
            Class<?> targetType = contentType == ContentReport.ContentType.DISCUSSION
                    ? vn.edu.hcmut.co3103.lms.entity.Discussion.class
                    : vn.edu.hcmut.co3103.lms.entity.Material.class;
            Object target = entityManager.find(targetType, contentId);
            if (target == null) throw new AppException(ErrorCode.REPORTED_CONTENT_NOT_FOUND);
            entityManager.refresh(target);
            if (target instanceof vn.edu.hcmut.co3103.lms.entity.Material material) {
                var lesson = entityManager.find(vn.edu.hcmut.co3103.lms.entity.Lesson.class, material.getLessonId());
                entityManager.refresh(lesson);
                entityManager.refresh(entityManager.find(vn.edu.hcmut.co3103.lms.entity.Chapter.class, lesson.getChapterId()));
            }
        } catch (jakarta.persistence.EntityNotFoundException deletedWhileWaiting) {
            throw new AppException(ErrorCode.REPORTED_CONTENT_NOT_FOUND);
        }
        requireTargetAccess(contentType, contentId, reporterId, tutor);
        if (contentReportRepository.existsByReporterIdAndContentTypeAndContentId(reporterId, contentType, contentId)) {
            throw new AppException(ErrorCode.DUPLICATE_CONTENT_REPORT);
        }
        ContentReport saved = contentReportRepository.saveAndFlush(
                new ContentReport(reporterId, contentType, contentId, reason, description));
        return ContentReportResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminContentReportResponse> list(ContentReport.ReportStatus status,
                                                         ContentReport.ContentType contentType,
                                                         int page, int size) {
        long offset = (page - 1L) * size;
        if (offset > Integer.MAX_VALUE) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        Page<ContentReport> reports = contentReportRepository.search(status, contentType,
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt", "reportId")));
        Set<UUID> discussions = existingTargets(reports.getContent(), ContentReport.ContentType.DISCUSSION);
        Set<UUID> materials = existingTargets(reports.getContent(), ContentReport.ContentType.MATERIAL);
        return new PageResponse<>(reports.getContent().stream().map(report -> AdminContentReportResponse.from(report,
                        (report.getContentType() == ContentReport.ContentType.DISCUSSION ? discussions : materials)
                                .contains(report.getContentId()))).toList(),
                page, size, reports.getTotalElements(), reports.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AdminContentReportResponse get(UUID reportId) {
        ContentReport report = requireReport(reportId);
        var target = targetReader.inspect(report);
        return AdminContentReportResponse.from(report, target != null, target);
    }

    @Transactional
    public AdminContentReportResponse review(UUID reportId, UUID administratorId, Map<String, Object> patch) {
        ContentReport report = contentReportRepository.findForReview(reportId)
                .orElseThrow(() -> new AppException(ErrorCode.CONTENT_REPORT_NOT_FOUND));
        entityManager.refresh(report);
        if (patch == null || patch.isEmpty() || !PATCHABLE_FIELDS.containsAll(patch.keySet())) {
            throw new AppException(ErrorCode.INVALID_REPORT_STATUS);
        }
        ContentReport.ReportStatus outcome = reviewOutcome(patch.get("status"));
        ContentReport.ReportStatus before = report.getStatus();
        if (before == ContentReport.ReportStatus.RESOLVED || before == ContentReport.ReportStatus.DISMISSED
                || (before == ContentReport.ReportStatus.REVIEWED && outcome == ContentReport.ReportStatus.REVIEWED)) {
            throw new AppException(ErrorCode.REPORT_ALREADY_UPDATED);
        }
        report.markReviewed(outcome, administratorId, Instant.now());
        auditLogs.saveAndFlush(AuditLog.contentReportReviewed(administratorId, reportId, before, outcome));
        return toAdminResponse(contentReportRepository.saveAndFlush(report));
    }

    private Set<UUID> existingTargets(List<ContentReport> reports, ContentReport.ContentType type) {
        List<UUID> ids = reports.stream().filter(r -> r.getContentType() == type)
                .map(ContentReport::getContentId).distinct().toList();
        if (ids.isEmpty()) return Set.of();
        return new HashSet<>(type == ContentReport.ContentType.DISCUSSION
                ? discussionRepository.findExistingIds(ids) : materialRepository.findExistingIds(ids));
    }

    @Transactional(readOnly = true)
    public ChapterService.DownloadableMaterial targetFile(UUID reportId) {
        ContentReport report = requireReport(reportId);
        if (report.getContentType() != ContentReport.ContentType.MATERIAL)
            throw new AppException(ErrorCode.REPORTED_CONTENT_NOT_FOUND);
        return chapterService.getModerationMaterialFile(report.getContentId());
    }

    private AdminContentReportResponse toAdminResponse(ContentReport report) {
        return AdminContentReportResponse.from(report, targetAvailable(report));
    }

    /**
     * The target is polymorphic and has no foreign key, so its existence is checked here. A report is
     * retained after its target is deleted; only its availability changes.
     */
    private boolean targetAvailable(ContentReport report) {
        return report.getContentType() == ContentReport.ContentType.DISCUSSION
                ? discussionRepository.existsById(report.getContentId())
                : materialRepository.existsById(report.getContentId());
    }

    /**
     * Reuses the read paths of the reported module so a reporter can only report content they are
     * already allowed to see, then reports the outcome with the codes of DOC-05 section 3.5.
     */
    private void requireTargetAccess(ContentReport.ContentType contentType, UUID contentId,
                                     UUID reporterId, boolean tutor) {
        try {
            if (contentType == ContentReport.ContentType.DISCUSSION) {
                discussionService.getDiscussion(contentId, reporterId, tutor);
            } else {
                chapterService.getMaterial(contentId, reporterId, tutor);
            }
        } catch (AppException exception) {
            if (exception.getErrorCode() == ErrorCode.DISCUSSION_NOT_FOUND
                    || exception.getErrorCode() == ErrorCode.CLASS_NOT_FOUND) {
                throw new AppException(ErrorCode.REPORTED_CONTENT_NOT_FOUND, exception);
            }
            throw exception;
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new AppException(ErrorCode.REPORTED_CONTENT_NOT_FOUND, exception);
            }
            if (exception.getStatusCode() == HttpStatus.FORBIDDEN) {
                throw new AppException(ErrorCode.FORBIDDEN, exception);
            }
            throw exception;
        }
    }

    private ContentReport requireReport(UUID reportId) {
        return contentReportRepository.findById(reportId)
                .orElseThrow(() -> new AppException(ErrorCode.CONTENT_REPORT_NOT_FOUND));
    }

    private ContentReport.ContentType contentType(String value) {
        try {
            return ContentReport.ContentType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private ContentReport.Reason reason(String value) {
        try {
            return ContentReport.Reason.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private ContentReport.ReportStatus reviewOutcome(Object value) {
        if (!(value instanceof String text)) throw new AppException(ErrorCode.INVALID_REPORT_STATUS);
        ContentReport.ReportStatus status;
        try {
            status = ContentReport.ReportStatus.valueOf(text);
        } catch (IllegalArgumentException exception) {
            throw new AppException(ErrorCode.INVALID_REPORT_STATUS);
        }
        // PENDING is the initial state, not a review outcome an Administrator can record.
        if (status == ContentReport.ReportStatus.PENDING) throw new AppException(ErrorCode.INVALID_REPORT_STATUS);
        return status;
    }

    private String optionalDescription(String description) {
        if (description == null) return null;
        if (description.isBlank()) throw new AppException(ErrorCode.VALIDATION_ERROR);
        return description.trim();
    }
}
