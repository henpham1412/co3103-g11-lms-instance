package vn.edu.hcmut.co3103.lms.repository.adaptive;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.SkillPrerequisite;
import vn.edu.hcmut.co3103.lms.entity.SkillPrerequisiteId;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SkillPrerequisiteRepository
        extends JpaRepository<SkillPrerequisite, SkillPrerequisiteId> {

    List<SkillPrerequisite> findAllById_SkillTopicId(UUID skillTopicId);

    List<SkillPrerequisite> findAllById_SkillTopicIdIn(
            Collection<UUID> skillTopicIds
    );

    boolean existsById_PrerequisiteSkillTopicId(
            UUID prerequisiteSkillTopicId
    );
}