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

    public ContentReportService(ContentReportRepository contentReportRepository,
                                DiscussionRepository discussionRepository,
                                MaterialRepository materialRepository,
                                DiscussionService discussionService,
                                ChapterService chapterService) {
        this.contentReportRepository = contentReportRepository;
        this.discussionRepository = discussionRepository;
        this.materialRepository = materialRepository;
        this.discussionService = discussionService;
        this.chapterService = chapterService;
    }

    @Transactional
    public ContentReportResponse create(UUID reporterId, boolean tutor, CreateContentReportRequest request) {
        if (!request.extraFields().isEmpty()) throw new AppException(ErrorCode.VALIDATION_ERROR);
        ContentReport.ContentType contentType = contentType(request.content_type());
        ContentReport.Reason reason = reason(request.reason());
        String description = optionalDescription(request.description());
        UUID contentId = request.content_id();
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
        Page<ContentReport> reports = contentReportRepository.search(status, contentType,
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PageResponse<>(reports.getContent().stream().map(this::toAdminResponse).toList(),
                page, size, reports.getTotalElements(), reports.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AdminContentReportResponse get(UUID reportId) {
        return toAdminResponse(requireReport(reportId));
    }

    @Transactional
    public AdminContentReportResponse review(UUID reportId, UUID administratorId, Map<String, Object> patch) {
        ContentReport report = requireReport(reportId);
        if (patch == null || patch.isEmpty() || !PATCHABLE_FIELDS.containsAll(patch.keySet())) {
            throw new AppException(ErrorCode.INVALID_REPORT_STATUS);
        }
        ContentReport.ReportStatus outcome = reviewOutcome(patch.get("status"));
        // DOC-05 section 5.3: the outcome is recorded once, while the report is still PENDING.
        if (report.getStatus() != ContentReport.ReportStatus.PENDING) {
            throw new AppException(ErrorCode.REPORT_ALREADY_UPDATED);
        }
        report.markReviewed(outcome, administratorId, Instant.now());
        return toAdminResponse(contentReportRepository.saveAndFlush(report));
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