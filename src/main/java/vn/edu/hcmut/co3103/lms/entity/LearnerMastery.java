package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import vn.edu.hcmut.co3103.lms.enums.MasteryLevel;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "learner_mastery", uniqueConstraints = @UniqueConstraint(
        name = "uq_learner_mastery_member_topic", columnNames = {"class_member_id", "skill_topic_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearnerMastery {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "learner_mastery_id", nullable = false)
    private UUID id;
    @Column(name = "class_member_id", nullable = false, updatable = false)
    private UUID classMemberId;
    @Column(name = "skill_topic_id", nullable = false, updatable = false)
    private UUID skillTopicId;
    @Enumerated(EnumType.STRING)
    @Column(name = "mastery_level", nullable = false, length = 20)
    private MasteryLevel masteryLevel;
    @Column(name = "mastery_score", nullable = false, columnDefinition = "numeric")
    private BigDecimal masteryScore;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public LearnerMastery(UUID classMemberId, UUID skillTopicId, BigDecimal score, MasteryLevel level) {
        this.classMemberId = classMemberId;
        this.skillTopicId = skillTopicId;
        update(score, level);
    }

    public void update(BigDecimal score, MasteryLevel level) {
        if (masteryScore == null || masteryScore.compareTo(score) != 0 || masteryLevel != level) {
            masteryScore = score;
            masteryLevel = level;
            updatedAt = OffsetDateTime.now();
        }
    }
}
