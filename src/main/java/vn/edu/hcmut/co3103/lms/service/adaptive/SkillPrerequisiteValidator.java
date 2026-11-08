package vn.edu.hcmut.co3103.lms.service.adaptive;

import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Validates prerequisite graphs without persistence or authorization dependencies. */
public final class SkillPrerequisiteValidator {

    private SkillPrerequisiteValidator() {
    }

    /** An edge points from a target topic to a topic it requires as a prerequisite. */
    public record PrerequisiteEdge(UUID skillTopicId, UUID prerequisiteSkillTopicId) {
        public PrerequisiteEdge {
            Objects.requireNonNull(skillTopicId, "skillTopicId");
            Objects.requireNonNull(prerequisiteSkillTopicId, "prerequisiteSkillTopicId");
        }
    }

    /**
     * Validates a graph against a topic-ID to class-ID lookup supplied by the caller.
     * Every referenced topic must be present. Separate classes may have independent
     * graphs, but an edge cannot cross classes. Inputs are not modified.
     *
     * @throws AppException for unknown topics, self-links, cross-class edges or cycles
     * @throws NullPointerException for null inputs, IDs, class IDs or edges
     */
    public static void validate(Map<UUID, UUID> topicClassIds, Set<PrerequisiteEdge> edges) {
        Map<UUID, UUID> topics = Map.copyOf(topicClassIds);
        Set<PrerequisiteEdge> graph = Set.copyOf(edges);
        Map<UUID, Set<UUID>> outgoing = new HashMap<>();
        Map<UUID, Integer> incomingCounts = new HashMap<>();

        for (PrerequisiteEdge edge : graph) {
            UUID target = edge.skillTopicId();
            UUID prerequisite = edge.prerequisiteSkillTopicId();
            UUID targetClass = requireClassId(topics, target);
            UUID prerequisiteClass = requireClassId(topics, prerequisite);
            if (target.equals(prerequisite)) {
                throw new AppException(ErrorCode.PREREQUISITE_SELF_REFERENCE);
            }
            if (!targetClass.equals(prerequisiteClass)) {
                throw new AppException(ErrorCode.PREREQUISITE_CLASS_MISMATCH);
            }
            outgoing.computeIfAbsent(target, ignored -> new HashSet<>()).add(prerequisite);
            incomingCounts.putIfAbsent(target, 0);
            incomingCounts.merge(prerequisite, 1, Integer::sum);
        }

        // Kahn's algorithm avoids recursion, including for long prerequisite chains.
        ArrayDeque<UUID> ready = new ArrayDeque<>();
        incomingCounts.forEach((topic, count) -> {
            if (count == 0) {
                ready.add(topic);
            }
        });
        int visited = 0;
        while (!ready.isEmpty()) {
            UUID topic = ready.remove();
            visited++;
            for (UUID prerequisite : outgoing.getOrDefault(topic, Set.of())) {
                int remaining = incomingCounts.merge(prerequisite, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(prerequisite);
                }
            }
        }
        if (visited != incomingCounts.size()) {
            throw new AppException(ErrorCode.PREREQUISITE_CYCLE);
        }
    }

    /**
     * Replaces only the target's outgoing prerequisite edges in a temporary graph,
     * then validates that resulting graph. An empty set removes all prerequisites.
     * Existing edges from other targets are retained; inputs are not modified.
     * Exception behavior is the same as {@link #validate(Map, Set)}.
     */
    public static void validateReplacement(Map<UUID, UUID> topicClassIds,
                                           Set<PrerequisiteEdge> existingEdges,
                                           UUID skillTopicId,
                                           Set<UUID> prerequisiteSkillTopicIds) {
        Map<UUID, UUID> topics = Map.copyOf(topicClassIds);
        UUID target = Objects.requireNonNull(skillTopicId, "skillTopicId");
        requireClassId(topics, target);
        Set<UUID> prerequisites = Set.copyOf(prerequisiteSkillTopicIds);
        Set<PrerequisiteEdge> resultingEdges = new HashSet<>();
        for (PrerequisiteEdge edge : Set.copyOf(existingEdges)) {
            if (!edge.skillTopicId().equals(target)) {
                resultingEdges.add(edge);
            }
        }
        for (UUID prerequisite : prerequisites) {
            resultingEdges.add(new PrerequisiteEdge(target, prerequisite));
        }
        validate(topics, resultingEdges);
    }

    private static UUID requireClassId(Map<UUID, UUID> topics, UUID topicId) {
        UUID classId = topics.get(topicId);
        if (classId == null) {
            throw new AppException(ErrorCode.SKILL_TOPIC_NOT_FOUND);
        }
        return classId;
    }
}
