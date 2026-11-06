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
import java.util.UUID;

/**
 * Placement of an assessment inside a lesson. Learning Content owns the placement, order and
 * visibility; the Quiz or Assignment configuration behind {@code activity_type} is owned by the
 * Assessment module.
 */
@Entity
@Table(name = "activity")
public class Activity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "activity_id", nullable = false, updatable = false)
    private UUID activityId;
    @Column(name = "lesson_id", nullable = false)
    private UUID lessonId;
    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;
    @Column(name = "description", columnDefinition = "text")
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false, length = 20)
    private ActivityType activityType;
    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Chapter.VisibilityStatus status = Chapter.VisibilityStatus.PUBLISHED;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Activity() { }

    public Activity(UUID lessonId, String title, String description, ActivityType activityType,
                    int sequenceOrder, Chapter.VisibilityStatus status) {
        this.lessonId = lessonId;
        this.title = title;
        this.description = description;
        this.activityType = activityType;
        this.sequenceOrder = sequenceOrder;
        this.status = status == null ? Chapter.VisibilityStatus.PUBLISHED : status;
    }

    @PrePersist
    void setCreatedAt() { if (createdAt == null) createdAt = Instant.now(); }
    public UUID getActivityId() { return activityId; }
    public UUID getLessonId() { return lessonId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public ActivityType getActivityType() { return activityType; }
    public int getSequenceOrder() { return sequenceOrder; }
    public Chapter.VisibilityStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setSequenceOrder(int sequenceOrder) { this.sequenceOrder = sequenceOrder; }
    public void setStatus(Chapter.VisibilityStatus status) { this.status = status; }

    public enum ActivityType { QUIZ, ASSIGNMENT }
}
