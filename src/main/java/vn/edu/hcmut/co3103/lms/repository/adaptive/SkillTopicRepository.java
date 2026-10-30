package vn.edu.hcmut.co3103.lms.repository.adaptive;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.SkillTopic;

import java.util.List;
import java.util.UUID;

public interface SkillTopicRepository
        extends JpaRepository<SkillTopic, UUID> {

    List<SkillTopic> findAllByClassId(UUID classId);

    boolean existsByClassIdAndName(UUID classId, String name);

    boolean existsByClassIdAndNameAndSkillTopicIdNot(
            UUID classId,
            String name,
            UUID skillTopicId
    );
}