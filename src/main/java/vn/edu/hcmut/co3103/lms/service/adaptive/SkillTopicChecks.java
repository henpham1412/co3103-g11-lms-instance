package vn.edu.hcmut.co3103.lms.service.adaptive;

import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.entity.SkillTopic;
import vn.edu.hcmut.co3103.lms.repository.adaptive.SkillTopicRepository;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Shared reference checks; no hierarchy or authorization implementation. */
final class SkillTopicChecks {

    private SkillTopicChecks() { }

    static SkillTopic requireTopic(SkillTopicRepository repository, UUID id) {
        requireId(id);
        return repository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.SKILL_TOPIC_NOT_FOUND));
    }

    static UUID requireClassId(SkillTopicRepository repository, UUID id) {
        requireId(id);
        return repository.findClassIdBySkillTopicId(id)
                .orElseThrow(() -> new AppException(ErrorCode.SKILL_TOPIC_NOT_FOUND));
    }

    static void requireId(UUID id) {
        if (id == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
    }

    static Set<UUID> uniqueIds(Collection<UUID> ids) {
        if (ids == null) {
            throw new AppException(ErrorCode.INVALID_TOPIC_ASSOCIATION);
        }
        Set<UUID> unique = new HashSet<>();
        for (UUID id : ids) {
            if (id == null || !unique.add(id)) {
                throw new AppException(ErrorCode.INVALID_TOPIC_ASSOCIATION);
            }
        }
        return Set.copyOf(unique);
    }

    static List<SkillTopic> requireTopicsInClass(SkillTopicRepository repository,
                                                Set<UUID> ids, UUID classId) {
        return requireTopicsInClass(repository, ids, classId, ErrorCode.RESOURCE_CLASS_MISMATCH);
    }

    static List<SkillTopic> requireTopicsInClass(SkillTopicRepository repository,
                                                Set<UUID> ids, UUID classId, ErrorCode mismatchCode) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<SkillTopic> topics = repository.findAllById(ids);
        if (topics.size() != ids.size()) {
            throw new AppException(ErrorCode.SKILL_TOPIC_NOT_FOUND);
        }
        if (topics.stream().anyMatch(topic -> !classId.equals(topic.getClassId()))) {
            throw new AppException(mismatchCode);
        }
        return topics;
    }
}
