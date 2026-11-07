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
@Table(name = "chapter")
public class Chapter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "chapter_id", nullable = false, updatable = false)
    private UUID chapterId;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VisibilityStatus status = VisibilityStatus.PUBLISHED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Chapter() { }

    public Chapter(UUID classId, String title, String description, int sequenceOrder, VisibilityStatus status) {
        this.classId = classId;
        this.title = title;
        this.description = description;
        this.sequenceOrder = sequenceOrder;
        this.status = status == null ? VisibilityStatus.PUBLISHED : status;
    }

    @PrePersist
    void setCreatedAt() { if (createdAt == null) createdAt = Instant.now(); }

    public UUID getChapterId() { return chapterId; }
    public UUID getClassId() { return classId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public int getSequenceOrder() { return sequenceOrder; }
    public VisibilityStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setSequenceOrder(int sequenceOrder) { this.sequenceOrder = sequenceOrder; }
    public void setStatus(VisibilityStatus status) { this.status = status; }

    public enum VisibilityStatus { PUBLISHED, HIDDEN }
}
