package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import vn.edu.hcmut.co3103.lms.enums.LearningSignalSourceType;
import vn.edu.hcmut.co3103.lms.enums.LearningSignalType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "learning_signal", uniqueConstraints = @UniqueConstraint(
        name = "uq_learning_signal_source", columnNames = {
        "class_member_id", "skill_topic_id", "signal_type", "source_type", "source_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LearningSignal {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "learning_signal_id", nullable = false)
    private UUID id;
    @Column(name = "class_member_id", nullable = false, updatable = false)
    private UUID classMemberId;
    @Column(name = "skill_topic_id", nullable = false, updatable = false)
    private UUID skillTopicId;
    @Enumerated(EnumType.STRING)
    @Column(name = "signal_type", nullable = false, updatable = false, length = 30)
    private LearningSignalType signalType;
    @Column(name = "signal_value", nullable = false, columnDefinition = "numeric")
    private BigDecimal signalValue;
    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, updatable = false, length = 30)
    private LearningSignalSourceType sourceType;
    @Column(name = "source_id", nullable = false, updatable = false)
    private UUID sourceId;

    public LearningSignal(UUID member, UUID topic, LearningSignalType type, BigDecimal value,
                          OffsetDateTime event, LearningSignalSourceType sourceType, UUID sourceId) {
        classMemberId = member;
        skillTopicId = topic;
        signalType = type;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        update(value, event);
    }

    public void update(BigDecimal value, OffsetDateTime event) {
        if (signalValue == null || signalValue.compareTo(value) != 0) signalValue = value;
        if (recordedAt == null || !recordedAt.isEqual(event)) recordedAt = event;
    }
}
