package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "discussion")
public class Discussion {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "discussion_id", nullable = false, updatable = false)
    private UUID discussionId;
    @Column(name = "class_id", nullable = false, updatable = false)
    private UUID classId;
    @Column(name = "lesson_id", updatable = false)
    private UUID lessonId;
    @Column(name = "author_id", nullable = false, updatable = false)
    private UUID authorId;
    @Column(name = "parent_id", updatable = false)
    private UUID parentId;
    @Column(name = "title", length = 255)
    private String title;
    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Discussion() { }

    public Discussion(UUID classId, UUID lessonId, UUID authorId, UUID parentId, String title, String content) {
        this.classId = classId;
        this.lessonId = lessonId;
        this.authorId = authorId;
        this.parentId = parentId;
        this.title = title;
        this.content = content;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getDiscussionId() { return discussionId; }
    public UUID getClassId() { return classId; }
    public UUID getLessonId() { return lessonId; }
    public UUID getAuthorId() { return authorId; }
    public UUID getParentId() { return parentId; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setTitle(String title) { this.title = title; }
    public void setContent(String content) { this.content = content; }
}