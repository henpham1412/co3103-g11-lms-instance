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
@Table(name = "quiz_attempt")
public class QuizAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "attempt_id", nullable = false)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_member_id", nullable = false)
    private ClassMember classMember;

    @NotNull
    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Size(max = 20)
    @NotNull
    @ColumnDefault("'IN_PROGRESS'")
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    @Column(name = "submitted_at")
    private OffsetDateTime submittedAt;

    @NotNull
    @ColumnDefault("false")
    @Column(name = "auto_submitted", nullable = false)
    private Boolean autoSubmitted;

    @Column(name = "score", precision = 5, scale = 2)
    private BigDecimal score;

    @Column(name = "graded_at")
    private OffsetDateTime gradedAt;


}