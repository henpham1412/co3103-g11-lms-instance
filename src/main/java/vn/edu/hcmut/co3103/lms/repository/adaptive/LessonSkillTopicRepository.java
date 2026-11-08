package vn.edu.hcmut.co3103.lms.repository.adaptive;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.LessonSkillTopic;
import vn.edu.hcmut.co3103.lms.entity.LessonSkillTopicId;

import java.util.List;
import java.util.UUID;

public interface LessonSkillTopicRepository
        extends JpaRepository<LessonSkillTopic, LessonSkillTopicId> {

    List<LessonSkillTopic> findAllById_LessonId(UUID lessonId);

    boolean existsById_SkillTopicId(UUID skillTopicId);
}