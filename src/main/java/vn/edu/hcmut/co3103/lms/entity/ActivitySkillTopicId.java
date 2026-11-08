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
public class ActivitySkillTopicId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Column(name = "activity_id")
    private UUID activityId;

    @Column(name = "skill_topic_id")
    private UUID skillTopicId;

    public ActivitySkillTopicId(UUID activityId, UUID skillTopicId) {
        this.activityId = activityId;
        this.skillTopicId = skillTopicId;
    }
}