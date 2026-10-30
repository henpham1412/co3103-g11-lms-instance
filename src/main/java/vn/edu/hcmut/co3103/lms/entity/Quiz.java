package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "quiz")
public class Quiz {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "quiz_id", nullable = false)
    private UUID id;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false)
    private Activity activity;

    @NotNull
    @Column(name = "time_limit_minutes", nullable = false)
    private Integer timeLimitMinutes;

    @NotNull
    @ColumnDefault("1")
    @Column(name = "attempts_allowed", nullable = false)
    private Integer attemptsAllowed;

    @Size(max = 20)
    @NotNull
    @ColumnDefault("'HIGHEST'")
    @Column(name = "grading_method", nullable = false, length = 20)
    private String gradingMethod;

    @Column(name = "opening_time")
    private OffsetDateTime openingTime;

    @NotNull
    @Column(name = "deadline", nullable = false)
    private OffsetDateTime deadline;

    @Column(name = "required_score", precision = 5, scale = 2)
    private BigDecimal requiredScore;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;


}
