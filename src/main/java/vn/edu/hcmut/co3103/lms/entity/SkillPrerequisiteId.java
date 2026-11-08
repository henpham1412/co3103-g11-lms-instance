package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SkillPrerequisiteId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "skill_topic_id")
    private UUID skillTopicId;

    @Column(name = "prerequisite_skill_topic_id")
    private UUID prerequisiteSkillTopicId;

    public SkillPrerequisiteId(
            UUID skillTopicId,
            UUID prerequisiteSkillTopicId
    ) {
        this.skillTopicId = skillTopicId;
        this.prerequisiteSkillTopicId = prerequisiteSkillTopicId;
    }
}