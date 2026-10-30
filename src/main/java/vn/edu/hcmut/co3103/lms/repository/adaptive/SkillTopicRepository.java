package vn.edu.hcmut.co3103.lms.repository.adaptive;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.hcmut.co3103.lms.entity.SkillTopic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SkillTopicRepository
        extends JpaRepository<SkillTopic, UUID> {

    List<SkillTopic> findAllByClassId(UUID classId);

    // Resolve the lock scope without loading a managed entity before acquiring it.
    @Query("select topic.classId from SkillTopic topic where topic.skillTopicId = :topicId")
    Optional<UUID> findClassIdBySkillTopicId(@Param("topicId") UUID topicId);

    boolean existsByClassIdAndName(UUID classId, String name);

    boolean existsByClassIdAndNameAndSkillTopicIdNot(
            UUID classId,
            String name,
            UUID skillTopicId
    );
}
