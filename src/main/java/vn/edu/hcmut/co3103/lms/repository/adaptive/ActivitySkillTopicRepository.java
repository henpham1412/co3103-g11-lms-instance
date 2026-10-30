package vn.edu.hcmut.co3103.lms.repository.adaptive;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.ActivitySkillTopic;
import vn.edu.hcmut.co3103.lms.entity.ActivitySkillTopicId;

import java.util.List;
import java.util.UUID;

public interface ActivitySkillTopicRepository
        extends JpaRepository<ActivitySkillTopic, ActivitySkillTopicId> {

    List<ActivitySkillTopic> findAllById_ActivityId(UUID activityId);

    boolean existsById_SkillTopicId(UUID skillTopicId);
}