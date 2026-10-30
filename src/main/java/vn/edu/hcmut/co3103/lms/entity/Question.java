package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "question")
public class Question {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "question_id", nullable = false)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @Size(max = 20)
    @NotNull
    @Column(name = "question_type", nullable = false, length = 20)
    private String questionType;

    @NotNull
    @Column(name = "content", nullable = false, length = Integer.MAX_VALUE)
    private String content;

    @NotNull
    @Column(name = "points_awarded", nullable = false, precision = 10, scale = 2)
    private BigDecimal pointsAwarded;

    @NotNull
    @ColumnDefault("0")
    @Column(name = "points_deducted", nullable = false, precision = 10, scale = 2)
    private BigDecimal pointsDeducted;

    @NotNull
    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;


}
