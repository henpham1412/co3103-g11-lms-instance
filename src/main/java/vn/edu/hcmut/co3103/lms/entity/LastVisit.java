package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "last_visit")
public class LastVisit {
    @Id
    @Column(name = "class_member_id", nullable = false, updatable = false)
    private UUID classMemberId;

    @Column(name = "lesson_id", nullable = false)
    private UUID lessonId;

    @Column(name = "visited_at", nullable = false)
    private Instant visitedAt;

    protected LastVisit() { }

    public LastVisit(UUID classMemberId, UUID lessonId, Instant visitedAt) {
        this.classMemberId = classMemberId;
        this.lessonId = lessonId;
        this.visitedAt = visitedAt;
    }

    @PrePersist
    void setVisitedAt() {
        if (visitedAt == null) visitedAt = Instant.now();
    }

    public UUID getClassMemberId() { return classMemberId; }
    public UUID getLessonId() { return lessonId; }
    public Instant getVisitedAt() { return visitedAt; }
    public void setLessonId(UUID lessonId) { this.lessonId = lessonId; }
    public void setVisitedAt(Instant visitedAt) { this.visitedAt = visitedAt; }
}
