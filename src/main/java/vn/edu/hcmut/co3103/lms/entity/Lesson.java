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

@Entity
@Table(name = "lesson")
public class Lesson {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "lesson_id", nullable = false, updatable = false)
    private UUID lessonId;
    @Column(name = "chapter_id", nullable = false)
    private UUID chapterId;
    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;
    @Column(name = "description", columnDefinition = "text")
    private String description;
    @Column(name = "estimated_duration")
    private Integer estimatedDuration;
    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Chapter.VisibilityStatus status = Chapter.VisibilityStatus.PUBLISHED;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Lesson() { }

    public Lesson(UUID chapterId, String title, String description, Integer estimatedDuration, int sequenceOrder, Chapter.VisibilityStatus status) {
        this.chapterId = chapterId;
        this.title = title;
        this.description = description;
        this.estimatedDuration = estimatedDuration;
        this.sequenceOrder = sequenceOrder;
        this.status = status == null ? Chapter.VisibilityStatus.PUBLISHED : status;
    }

    @PrePersist
    void setCreatedAt() { if (createdAt == null) createdAt = Instant.now(); }
    public UUID getLessonId() { return lessonId; }
    public UUID getChapterId() { return chapterId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Integer getEstimatedDuration() { return estimatedDuration; }
    public int getSequenceOrder() { return sequenceOrder; }
    public Chapter.VisibilityStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setEstimatedDuration(Integer estimatedDuration) { this.estimatedDuration = estimatedDuration; }
    public void setSequenceOrder(int sequenceOrder) { this.sequenceOrder = sequenceOrder; }
    public void setStatus(Chapter.VisibilityStatus status) { this.status = status; }
}
