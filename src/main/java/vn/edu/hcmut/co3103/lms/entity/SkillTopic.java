package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.edu.hcmut.co3103.lms.entity.common.AuditedEntity;
import vn.edu.hcmut.co3103.lms.entity.converter.TopicDifficultyConverter;
import vn.edu.hcmut.co3103.lms.enums.TopicDifficulty;

import java.util.UUID;

@Entity
@Table(
        name = "skill_topics",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_skill_topics_class_name",
                columnNames = {"class_id", "name"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SkillTopic extends AuditedEntity {

    @Id
    @Column(name = "skill_topic_id", nullable = false)
    private UUID skillTopicId;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @NotNull
    @Size(max = 150)
    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Setter
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Setter
    @Convert(converter = TopicDifficultyConverter.class)
    @Column(name = "difficulty_level", columnDefinition = "SMALLINT")
    private TopicDifficulty difficultyLevel;

    public SkillTopic(
            UUID skillTopicId,
            UUID classId,
            String name,
            String description,
            TopicDifficulty difficultyLevel
    ) {
        this.skillTopicId = skillTopicId;
        this.classId = classId;
        setName(name);
        this.description = description;
        this.difficultyLevel = difficultyLevel;
    }

    public void setName(String name) {
        this.name = normalizeName(name);
    }

    @PrePersist
    @PreUpdate
    protected void normalizeBeforePersistence() {
        this.name = normalizeName(this.name);
    }

    private static String normalizeName(String name) {
        return name == null ? null : name.trim();
    }
}