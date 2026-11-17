package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.entity.ContentReport;

import java.time.Instant;
import java.util.UUID;

/** Exactly the 201 body of DOC-05 section 3.5; the reporter is not echoed back to the submitter. */
public record ContentReportResponse(
        @JsonProperty("report_id") UUID reportId,
        @JsonProperty("content_type") ContentReport.ContentType contentType,
        @JsonProperty("content_id") UUID contentId,
        ContentReport.Reason reason,
        ContentReport.ReportStatus status,
        @JsonProperty("created_at") Instant createdAt
) {
    public static ContentReportResponse from(ContentReport report) {
        return new ContentReportResponse(report.getReportId(), report.getContentType(), report.getContentId(),
                report.getReason(), report.getStatus(), report.getCreatedAt());
    }
}