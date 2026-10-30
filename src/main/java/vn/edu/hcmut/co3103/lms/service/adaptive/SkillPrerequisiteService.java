package vn.edu.hcmut.co3103.lms.service.adaptive;

import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.entity.SkillPrerequisite;
import vn.edu.hcmut.co3103.lms.entity.SkillPrerequisiteId;
import vn.edu.hcmut.co3103.lms.entity.SkillTopic;
import vn.edu.hcmut.co3103.lms.repository.adaptive.SkillPrerequisiteRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.SkillTopicRepository;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillPrerequisiteValidator.PrerequisiteEdge;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.ClassAccess;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Register as a transactional Spring bean only with a real ClassAccess adapter. */
@Transactional(readOnly = true)
public class SkillPrerequisiteService {

    private final SkillTopicRepository topics;
    private final SkillPrerequisiteRepository prerequisites;
    private final ClassAccess access;

    public SkillPrerequisiteService(SkillTopicRepository topics,
                                    SkillPrerequisiteRepository prerequisites, ClassAccess access) {
        this.topics = topics;
        this.prerequisites = prerequisites;
        this.access = access;
    }

    public List<SkillPrerequisite> list(UUID topicId) {
        SkillTopic topic = SkillTopicChecks.requireTopic(topics, topicId);
        access.requireReadAccess(topic.getClassId());
        return prerequisites.findAllById_SkillTopicId(topicId);
    }

    @Transactional
    public void replace(UUID topicId, Collection<UUID> prerequisiteIds) {
        Set<UUID> requested = SkillTopicChecks.uniqueIds(prerequisiteIds);
        UUID classId = SkillTopicChecks.requireClassId(topics, topicId);
        access.lockForWrite(classId);
        SkillTopicChecks.requireTopic(topics, topicId);
        SkillTopicChecks.requireTopicsInClass(topics, requested, classId, ErrorCode.PREREQUISITE_CLASS_MISMATCH);

        Map<UUID, UUID> classTopics = topics.findAllByClassId(classId).stream()
                .collect(Collectors.toMap(SkillTopic::getSkillTopicId, SkillTopic::getClassId));
        List<SkillPrerequisite> existing = prerequisites.findAllById_SkillTopicIdIn(classTopics.keySet());
        Set<PrerequisiteEdge> graph = existing.stream().map(edge -> new PrerequisiteEdge(
                edge.getId().getSkillTopicId(), edge.getId().getPrerequisiteSkillTopicId()))
                .collect(Collectors.toSet());
        SkillPrerequisiteValidator.validateReplacement(classTopics, graph, topicId, requested);

        List<SkillPrerequisite> outgoing = existing.stream()
                .filter(edge -> topicId.equals(edge.getId().getSkillTopicId())).toList();
        Set<UUID> current = outgoing.stream().map(edge -> edge.getId().getPrerequisiteSkillTopicId())
                .collect(Collectors.toSet());
        prerequisites.deleteAll(outgoing.stream()
                .filter(edge -> !requested.contains(edge.getId().getPrerequisiteSkillTopicId())).toList());
        prerequisites.saveAll(requested.stream().filter(id -> !current.contains(id))
                .map(id -> new SkillPrerequisite(new SkillPrerequisiteId(topicId, id))).toList());
        prerequisites.flush();
    }
}
