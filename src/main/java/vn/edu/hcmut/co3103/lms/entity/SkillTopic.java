package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.edu.hcmut.co3103.lms.enums.TopicDifficulty;

import java.time.Instant;
import java.util.UUID;
@Entity
@Table(
        name = "skill_topics",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"class_id", "name"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SkillTopic {

    @Id
    @Column(name = "skill_topic_id")
    private UUID skillTopicId;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Setter
    @Column(name = "name")
    private String name;

    @Setter
    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Setter
    @Column(name = "difficulty_level")
    private TopicDifficulty difficultyLevel;

    @Column(name = "created_at")
    private Instant createdAt;

    @Setter
    @Column(name = "updated_at")
    private Instant updatedAt;

    public SkillTopic(
            UUID skillTopicId,
            UUID classId,
            String name,
            String description,
            TopicDifficulty difficultyLevel,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.skillTopicId = skillTopicId;
        this.classId = classId;
        this.name = name;
        this.description = description;
        this.difficultyLevel = difficultyLevel;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}