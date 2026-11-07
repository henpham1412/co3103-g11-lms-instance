package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "completion_mark", uniqueConstraints = @UniqueConstraint(
        name = "uq_completion_mark_member_lesson",
        columnNames = {"class_member_id", "lesson_id"}
))
public class CompletionMark {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "mark_id", nullable = false, updatable = false)
    private UUID markId;

    @Column(name = "class_member_id", nullable = false, updatable = false)
    private UUID classMemberId;

    @Column(name = "lesson_id", nullable = false, updatable = false)
    private UUID lessonId;

    @Column(name = "marked_at", nullable = false)
    private Instant markedAt;

    protected CompletionMark() { }

    public CompletionMark(UUID classMemberId, UUID lessonId, Instant markedAt) {
        this.classMemberId = classMemberId;
        this.lessonId = lessonId;
        this.markedAt = markedAt;
    }

    @PrePersist
    void setMarkedAt() {
        if (markedAt == null) markedAt = Instant.now();
    }

    public UUID getMarkId() { return markId; }
    public UUID getClassMemberId() { return classMemberId; }
    public UUID getLessonId() { return lessonId; }
    public Instant getMarkedAt() { return markedAt; }
}
