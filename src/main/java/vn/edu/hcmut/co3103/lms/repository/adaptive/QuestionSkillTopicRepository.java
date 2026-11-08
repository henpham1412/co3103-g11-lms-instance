package vn.edu.hcmut.co3103.lms.repository.adaptive;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.QuestionSkillTopic;
import vn.edu.hcmut.co3103.lms.entity.QuestionSkillTopicId;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface QuestionSkillTopicRepository
        extends JpaRepository<QuestionSkillTopic, QuestionSkillTopicId> {

    List<QuestionSkillTopic> findAllById_QuestionId(UUID questionId);

    List<QuestionSkillTopic> findAllById_QuestionIdIn(Collection<UUID> questionIds);

    boolean existsById_SkillTopicId(UUID skillTopicId);
}
