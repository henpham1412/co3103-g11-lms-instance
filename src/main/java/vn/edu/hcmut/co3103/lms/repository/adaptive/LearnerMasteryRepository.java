package vn.edu.hcmut.co3103.lms.repository.adaptive;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.LearnerMastery;
import java.util.Optional;
import java.util.UUID;

public interface LearnerMasteryRepository extends JpaRepository<LearnerMastery, UUID> {
    Optional<LearnerMastery> findByClassMemberIdAndSkillTopicId(UUID classMemberId, UUID skillTopicId);
}
