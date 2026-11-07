package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "class_member")
public class ClassMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "class_member_id", nullable = false, updatable = false)
    private UUID classMemberId;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MembershipStatus status;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "joined_at")
    private Instant joinedAt;

    protected ClassMember() {
        // Required by JPA.
    }

    public ClassMember(
            UUID classId,
            UUID studentId,
            MembershipStatus status,
            Instant requestedAt,
            Instant joinedAt
    ) {
        this.classId = classId;
        this.studentId = studentId;
        this.status = status;
        this.requestedAt = requestedAt;
        this.joinedAt = joinedAt;
    }

    public UUID getClassMemberId() {
        return classMemberId;
    }

    public UUID getClassId() {
        return classId;
    }

    public void setClassId(UUID classId) {
        this.classId = classId;
    }

    public UUID getStudentId() {
        return studentId;
    }

    public void setStudentId(UUID studentId) {
        this.studentId = studentId;
    }

    public MembershipStatus getStatus() {
        return status;
    }

    public void setStatus(MembershipStatus status) {
        this.status = status;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public void setRequestedAt(Instant requestedAt) {
        this.requestedAt = requestedAt;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }

    public enum MembershipStatus {
        PENDING,
        ACTIVE,
        REJECTED,
        INACTIVE
    }
}
