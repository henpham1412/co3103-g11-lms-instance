package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "lesson_skill_topics")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LessonSkillTopic {

    @EmbeddedId
    private LessonSkillTopicId id;

    public LessonSkillTopic(LessonSkillTopicId id) {
        this.id = id;
    }
}