package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A report against a Discussion or a Material (DOC-04 section 2.5.2). The reporter, target and
 * reason are fixed at submission; only the review outcome may change afterwards.
 */
@Entity
@Table(name = "content_report")
public class ContentReport {

    // Only a final outcome releases the target for physical deletion (DOC-05 sections 3.2.5, 3.3).
    public static final Set<ReportStatus> UNRESOLVED_STATUSES = Set.of(ReportStatus.PENDING, ReportStatus.REVIEWED);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "report_id", nullable = false, updatable = false)
    private UUID reportId;

    @Column(name = "reporter_id", nullable = false, updatable = false)
    private UUID reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, updatable = false, length = 20)
    private ContentType contentType;

    @Column(name = "content_id", nullable = false, updatable = false)
    private UUID contentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 20)
    private Reason reason;

    @Column(name = "description", updatable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReportStatus status = ReportStatus.PENDING;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ContentReport() { }

    public ContentReport(UUID reporterId, ContentType contentType, UUID contentId, Reason reason, String description) {
        this.reporterId = reporterId;
        this.contentType = contentType;
        this.contentId = contentId;
        this.reason = reason;
        this.description = description;
    }

    @PrePersist
    void setCreatedAt() { if (createdAt == null) createdAt = Instant.now(); }

    public void markReviewed(ReportStatus outcome, UUID reviewerId, Instant reviewedAt) {
        this.status = outcome;
        this.reviewedBy = reviewerId;
        this.reviewedAt = reviewedAt;
    }

    public UUID getReportId() { return reportId; }
    public UUID getReporterId() { return reporterId; }
    public ContentType getContentType() { return contentType; }
    public UUID getContentId() { return contentId; }
    public Reason getReason() { return reason; }
    public String getDescription() { return description; }
    public ReportStatus getStatus() { return status; }
    public UUID getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public enum ContentType { DISCUSSION, MATERIAL }

    public enum Reason { SPAM, HARASSMENT, INAPPROPRIATE, OTHER }

    public enum ReportStatus { PENDING, REVIEWED, RESOLVED, DISMISSED }
}