package vn.edu.hcmut.co3103.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "audit_log_id", nullable = false, updatable = false)
    private UUID auditLogId;

    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;

    @Column(nullable = false, updatable = false, length = 100)
    private String action;

    @Column(name = "entity_type", nullable = false, updatable = false, length = 100)
    private String entityType;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AuditLog(UUID actorId, LmsUserAccess target, LmsRole previousRole, AccessStatus previousStatus) {
        this.actorId = actorId;
        this.action = "LMS_USER_ACCESS_UPDATED";
        this.entityType = "LmsUserAccess";
        this.entityId = target.getUserId();
        // Only enum names are interpolated, never user-provided strings or profile data.
        this.details = """
                {"before":{"lms_role":"%s","access_status":"%s"},"after":{"lms_role":"%s","access_status":"%s"}}
                """.formatted(previousRole, previousStatus, target.getLmsRole(), target.getAccessStatus()).strip();
        this.createdAt = Instant.now();
    }
}
