package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "assignment")
public class Assignment {
    @Setter
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "assignment_id", nullable = false, updatable = false)
    private UUID assignmentId;

    @Column(name = "activity_id", nullable = false, unique = true, updatable = false)
    private UUID activityId;

    @Setter
    @Column(name = "opening_time")
    private OffsetDateTime openingTime;

    @Setter
    @Column(name = "deadline", nullable = false)
    private OffsetDateTime deadline;

    @Setter
    @Column(name = "allow_late", nullable = false)
    private boolean allowLate;

    @Setter
    @Column(name = "allowed_file_types", length = 1000)
    private String allowedFileTypes;

    @Setter
    @Column(name = "max_file_size_mb")
    private Integer maxFileSizeMb;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Assignment() { }

    public Assignment(UUID activityId, OffsetDateTime openingTime, OffsetDateTime deadline,
                      boolean allowLate, String allowedFileTypes, Integer maxFileSizeMb) {
        this.activityId = activityId;
        this.openingTime = openingTime;
        this.deadline = deadline;
        this.allowLate = allowLate;
        this.allowedFileTypes = allowedFileTypes;
        this.maxFileSizeMb = maxFileSizeMb;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = OffsetDateTime.now(); }

    public UUID getAssignmentId() { return assignmentId; }

    public UUID getActivityId() { return activityId; }
    public OffsetDateTime getOpeningTime() { return openingTime; }

    public OffsetDateTime getDeadline() { return deadline; }

    public boolean isAllowLate() { return allowLate; }

    public String getAllowedFileTypes() { return allowedFileTypes; }

    public Integer getMaxFileSizeMb() { return maxFileSizeMb; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
