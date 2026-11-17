package vn.edu.hcmut.co3103.lms.service.adaptive.recommendation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillPrerequisiteValidator;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillPrerequisiteValidator.PrerequisiteEdge;

import java.util.*;

@Component
public class TopicPrerequisiteResolver {
    private final JdbcTemplate jdbc;

    public TopicPrerequisiteResolver(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Two class-scoped scalar queries, never a query per topic or recursive SQL. */
    public Graph read(UUID classId) {
        Map<UUID, UUID> classes = new HashMap<>();
        jdbc.query("SELECT skill_topic_id FROM skill_topics WHERE class_id=?", row -> {
            classes.put(row.getObject("skill_topic_id", UUID.class), classId);
        }, classId);
        Set<PrerequisiteEdge> edges = new HashSet<>(jdbc.query("""
                SELECT p.skill_topic_id,p.prerequisite_skill_topic_id FROM skill_prerequisites p
                JOIN skill_topics target ON target.skill_topic_id=p.skill_topic_id
                WHERE target.class_id=?
                """, (row, n) -> new PrerequisiteEdge(row.getObject(1, UUID.class), row.getObject(2, UUID.class)), classId));
        // Reject corrupted cross-class edges/cycles using the canonical LMS-8 validator.
        SkillPrerequisiteValidator.validate(classes, edges);
        return new Graph(classes.keySet(), edges);
    }

    public static final class Graph {
        private final Set<UUID> topics;
        private final Map<UUID, Set<UUID>> direct;

        public Graph(Set<UUID> topics, Set<PrerequisiteEdge> edges) {
            this.topics = Set.copyOf(topics);
            SkillPrerequisiteValidator.validate(this.topics.stream().collect(
                    java.util.stream.Collectors.toMap(id -> id, ignored -> new UUID(0, 0))), edges);
            Map<UUID, Set<UUID>> mutable = new HashMap<>();
            edges.forEach(edge -> mutable.computeIfAbsent(edge.skillTopicId(), ignored -> new HashSet<>())
                    .add(edge.prerequisiteSkillTopicId()));
            Map<UUID, Set<UUID>> copy = new HashMap<>();
            mutable.forEach((key, value) -> copy.put(key, Set.copyOf(value)));
            direct = Map.copyOf(copy);
        }

        public Set<UUID> topics() { return topics; }

        /** Each node is visited at most once per requested target, including diamond DAGs. */
        public Set<UUID> ancestors(UUID target) {
            Set<UUID> visited = new HashSet<>();
            ArrayDeque<UUID> pending = new ArrayDeque<>(direct.getOrDefault(target, Set.of()));
            while (!pending.isEmpty()) {
                UUID next = pending.remove();
                if (visited.add(next)) pending.addAll(direct.getOrDefault(next, Set.of()));
            }
            return Set.copyOf(visited);
        }
    }
}
