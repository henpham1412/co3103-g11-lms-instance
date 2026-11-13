package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "submission")
public class Submission {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "submission_id", nullable = false, updatable = false)
    private UUID submissionId;
    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;
    @Column(name = "class_member_id", nullable = false)
    private UUID classMemberId;
    @Column(name = "file_name", nullable = false)
    private String fileName;
    @Column(name = "storage_path", nullable = false)
    private String storagePath;
    @Column(name = "mime_type", nullable = false)
    private String mimeType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(name = "submitted_at", nullable = false)
    private OffsetDateTime submittedAt;
    @Column(name = "is_late", nullable = false)
    private boolean late;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.SUBMITTED;
    @Column(name = "score", precision = 5, scale = 2)
    private BigDecimal score;
    @Column(name = "feedback")
    private String feedback;
    @Column(name = "graded_by")
    private UUID gradedBy;
    @Column(name = "graded_at")
    private OffsetDateTime gradedAt;
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Submission() { }

    public Submission(UUID assignmentId, UUID classMemberId, String fileName, String storagePath,
                      String mimeType, long sizeBytes, OffsetDateTime submittedAt, boolean late) {
        this.assignmentId = assignmentId;
        this.classMemberId = classMemberId;
        this.fileName = fileName;
        this.storagePath = storagePath;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.submittedAt = submittedAt;
        this.late = late;
    }

    public void replaceFile(String fileName, String storagePath, String mimeType, long sizeBytes,
                            OffsetDateTime submittedAt, boolean late) {
        this.fileName = fileName;
        this.storagePath = storagePath;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.submittedAt = submittedAt;
        this.late = late;
        this.status = Status.SUBMITTED;
        this.score = null;
        this.feedback = null;
        this.gradedBy = null;
        this.gradedAt = null;
    }

    public void grade(BigDecimal score, String feedback, UUID gradedBy, OffsetDateTime gradedAt) {
        this.score = score;
        this.feedback = feedback;
        this.gradedBy = gradedBy;
        this.gradedAt = gradedAt;
        this.status = Status.GRADED;
    }

    public UUID getSubmissionId() { return submissionId; }
    public UUID getAssignmentId() { return assignmentId; }
    public UUID getClassMemberId() { return classMemberId; }
    public String getFileName() { return fileName; }
    public String getStoragePath() { return storagePath; }
    public String getMimeType() { return mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public OffsetDateTime getSubmittedAt() { return submittedAt; }
    public boolean isLate() { return late; }
    public Status getStatus() { return status; }
    public BigDecimal getScore() { return score; }
    public String getFeedback() { return feedback; }
    public UUID getGradedBy() { return gradedBy; }
    public OffsetDateTime getGradedAt() { return gradedAt; }
    public long getVersion() { return version; }

    public enum Status { SUBMITTED, GRADED }
}
