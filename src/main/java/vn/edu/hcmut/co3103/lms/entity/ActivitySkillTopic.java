package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "activity_skill_topics")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ActivitySkillTopic {

    @EmbeddedId
    private ActivitySkillTopicId id;

    public ActivitySkillTopic(ActivitySkillTopicId id) {
        this.id = id;
    }
}