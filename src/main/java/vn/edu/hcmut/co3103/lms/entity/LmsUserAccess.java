package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "lms_user_access")
public class LmsUserAccess {
    @Id
    @Column(name = "user_id", nullable = false)
    private UUID id;

    @Size(max = 150)
    @NotNull
    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Size(max = 254)
    @NotNull
    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Size(max = 20)
    @NotNull
    @Column(name = "lms_role", nullable = false, length = 20)
    private String lmsRole;

    @Size(max = 20)
    @NotNull
    @ColumnDefault("'ACTIVE'")
    @Column(name = "access_status", nullable = false, length = 20)
    private String accessStatus;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;


}