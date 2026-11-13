package vn.edu.hcmut.co3103.lms.repository.adaptive;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.LearningSignal;
import vn.edu.hcmut.co3103.lms.enums.LearningSignalType;
import java.util.List;
import java.util.UUID;

public interface LearningSignalRepository extends JpaRepository<LearningSignal, UUID> {
    List<LearningSignal> findByClassMemberIdAndSkillTopicIdAndSignalType(
            UUID classMemberId, UUID skillTopicId, LearningSignalType signalType);
    List<LearningSignal> findByClassMemberIdAndSignalType(UUID classMemberId, LearningSignalType signalType);
}
