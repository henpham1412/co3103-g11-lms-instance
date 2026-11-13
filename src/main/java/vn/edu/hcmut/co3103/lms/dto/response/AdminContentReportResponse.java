package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.entity.ContentReport;

import java.time.Instant;
import java.util.UUID;

/**
 * The administrator view of a ContentReport (DOC-04 section 2.5.2). {@code target_available} is how
 * DOC-05 section 5.3 tells the Administrator that a retained report can no longer display its target.
 */
public record AdminContentReportResponse(
        @JsonProperty("report_id") UUID reportId,
        @JsonProperty("reporter_id") UUID reporterId,
        @JsonProperty("content_type") ContentReport.ContentType contentType,
        @JsonProperty("content_id") UUID contentId,
        ContentReport.Reason reason,
        String description,
        ContentReport.ReportStatus status,
        @JsonProperty("reviewed_by") UUID reviewedBy,
        @JsonProperty("reviewed_at") Instant reviewedAt,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("target_available") boolean targetAvailable
) {
    public static AdminContentReportResponse from(ContentReport report, boolean targetAvailable) {
        return new AdminContentReportResponse(report.getReportId(), report.getReporterId(),
                report.getContentType(), report.getContentId(), report.getReason(), report.getDescription(),
                report.getStatus(), report.getReviewedBy(), report.getReviewedAt(), report.getCreatedAt(),
                targetAvailable);
    }
}