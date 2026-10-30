package vn.edu.hcmut.co3103.lms.service.adaptive;

import jakarta.persistence.EntityNotFoundException;
import vn.edu.hcmut.co3103.lms.entity.SkillTopic;
import vn.edu.hcmut.co3103.lms.repository.adaptive.SkillTopicRepository;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Shared reference checks; no hierarchy or authorization implementation. */
final class SkillTopicChecks {

    private SkillTopicChecks() { }

    static SkillTopic requireTopic(SkillTopicRepository repository, UUID id) {
        Objects.requireNonNull(id, "skillTopicId");
        return repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Skill topic not found: " + id));
    }

    static UUID requireClassId(SkillTopicRepository repository, UUID id) {
        Objects.requireNonNull(id, "skillTopicId");
        return repository.findClassIdBySkillTopicId(id)
                .orElseThrow(() -> new EntityNotFoundException("Skill topic not found: " + id));
    }

    static Set<UUID> uniqueIds(Collection<UUID> ids) {
        Objects.requireNonNull(ids, "skillTopicIds");
        Set<UUID> unique = new HashSet<>();
        for (UUID id : ids) {
            if (id == null || !unique.add(id)) {
                throw new IllegalArgumentException("Topic IDs must be non-null and distinct");
            }
        }
        return Set.copyOf(unique);
    }

    static List<SkillTopic> requireTopicsInClass(SkillTopicRepository repository,
                                                Set<UUID> ids, UUID classId) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<SkillTopic> topics = repository.findAllById(ids);
        if (topics.size() != ids.size()) {
            throw new EntityNotFoundException("One or more skill topics do not exist");
        }
        if (topics.stream().anyMatch(topic -> !classId.equals(topic.getClassId()))) {
            throw new IllegalArgumentException("All topics must belong to the resource's class");
        }
        return topics;
    }
}
