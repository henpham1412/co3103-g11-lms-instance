package vn.edu.hcmut.co3103.lms.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "lms_setup_state")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LmsSetupState {

    public static final String GLOBAL_ID = "GLOBAL";

    @Id
    @Column(name = "id", nullable = false, length = 32)
    private String id;

    @Column(name = "is_completed", nullable = false)
    private boolean completed;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "bootstrapped_by")
    private UUID bootstrappedBy;

    // Records the local administrator associated with closure, not the deployment operator.
    public void complete(UUID userId) {
        if (completed) {
            throw new IllegalStateException("Administrator setup is already complete.");
        }
        Objects.requireNonNull(userId, "Administrator identity is required to complete setup.");
        completed = true;
        completedAt = Instant.now();
        bootstrappedBy = userId;
    }
}
