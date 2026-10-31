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
@Table(name = "material")
public class Material {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "material_id", nullable = false, updatable = false)
    private UUID materialId;
    @Column(name = "lesson_id", nullable = false)
    private UUID lessonId;
    @Column(name = "title", nullable = false, columnDefinition = "text")
    private String title;
    @Enumerated(EnumType.STRING)
    @Column(name = "material_type", nullable = false, length = 20)
    private MaterialType materialType;
    @Column(name = "file_url", columnDefinition = "text")
    private String fileUrl;
    @Column(name = "content", columnDefinition = "text")
    private String content;
    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Chapter.VisibilityStatus status = Chapter.VisibilityStatus.PUBLISHED;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Material() { }

    public Material(UUID lessonId, String title, MaterialType materialType, String fileUrl, String content, int sequenceOrder, Chapter.VisibilityStatus status) {
        this.lessonId = lessonId;
        this.title = title;
        this.materialType = materialType;
        this.fileUrl = fileUrl;
        this.content = content;
        this.sequenceOrder = sequenceOrder;
        this.status = status == null ? Chapter.VisibilityStatus.PUBLISHED : status;
    }

    @PrePersist
    void setCreatedAt() { if (createdAt == null) createdAt = Instant.now(); }
    public UUID getMaterialId() { return materialId; }
    public UUID getLessonId() { return lessonId; }
    public String getTitle() { return title; }
    public MaterialType getMaterialType() { return materialType; }
    public String getFileUrl() { return fileUrl; }
    public String getContent() { return content; }
    public int getSequenceOrder() { return sequenceOrder; }
    public Chapter.VisibilityStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setTitle(String title) { this.title = title; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public void setContent(String content) { this.content = content; }
    public void setSequenceOrder(int sequenceOrder) { this.sequenceOrder = sequenceOrder; }
    public void setStatus(Chapter.VisibilityStatus status) { this.status = status; }

    public enum MaterialType { DOCUMENT, VIDEO, TEXT, URL }
}
