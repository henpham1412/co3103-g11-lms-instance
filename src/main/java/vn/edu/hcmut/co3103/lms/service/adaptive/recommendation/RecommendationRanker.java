package vn.edu.hcmut.co3103.lms.service.adaptive.recommendation;

import org.springframework.stereotype.Component;
import vn.edu.hcmut.co3103.lms.enums.MasteryLevel;

import java.math.BigDecimal;
import java.util.*;

/** D-10..D-13 coordinator working rules. Pure selection over prepared, eligible data. */
@Component
public final class RecommendationRanker {
    public record Mastery(MasteryLevel level, BigDecimal score) {
        public Mastery { Objects.requireNonNull(level); Objects.requireNonNull(score); }
    }
    public enum Reason { ADDITIONAL_PRACTICE, NORMAL_SEQUENCE, NO_ELIGIBLE_ACTIVITY, CLASS_ARCHIVED }
    public record Result(RecommendationCandidate activity, Reason reason, UUID reinforcedTopicId) {
        public static Result empty(Reason reason) { return new Result(null, reason, null); }
    }

    public Result select(List<RecommendationCandidate> candidates, Map<UUID, Mastery> mastery,
                         TopicPrerequisiteResolver.Graph graph) {
        List<RecommendationCandidate> eligible = List.copyOf(candidates);
        Map<UUID, Mastery> estimates = Map.copyOf(mastery);
        Map<UUID, RecommendationCandidate> bestByTopic = new HashMap<>();
        for (var candidate : eligible) {
            for (UUID topic : candidate.topicIds()) {
                if (!graph.topics().contains(topic)) continue;
                bestByTopic.merge(topic, candidate, (a, b) -> RecommendationCandidate.SEQUENCE.compare(a, b) <= 0 ? a : b);
            }
        }
        for (MasteryLevel level : List.of(MasteryLevel.WEAK, MasteryLevel.DEVELOPING)) {
            Set<UUID> group = new HashSet<>(bestByTopic.keySet());
            group.removeIf(topic -> !estimates.containsKey(topic) || estimates.get(topic).level() != level);
            // Choose from the topological frontier, not a non-transitive pairwise comparator.
            // No suitable practice for an ancestor does not block otherwise accessible content.
            UUID selected = group.stream()
                    .filter(topic -> Collections.disjoint(graph.ancestors(topic), group))
                    .min(Comparator.<UUID, BigDecimal>comparing(topic -> estimates.get(topic).score())
                            .thenComparing(bestByTopic::get, RecommendationCandidate.SEQUENCE)
                            .thenComparing(UUID::toString)).orElse(null);
            if (selected != null) return new Result(bestByTopic.get(selected), Reason.ADDITIONAL_PRACTICE, selected);
        }

        // A submitted Quiz is not an invented completion state. Prefer a new learning
        // action, but retain canonical permitted retries when no progression exists.
        List<RecommendationCandidate> progression = eligible.stream()
                .filter(c -> !c.hasSubmittedAttempt() && !sufficientOnly(c, estimates)).toList();
        if (progression.isEmpty()) progression = eligible.stream().filter(c -> !sufficientOnly(c, estimates)).toList();
        if (progression.isEmpty()) progression = eligible;
        return progression.stream().min(Comparator.comparing(RecommendationCandidate::lessonCompleted)
                        .thenComparing(RecommendationCandidate.SEQUENCE))
                .map(c -> new Result(c, Reason.NORMAL_SEQUENCE, null))
                .orElseGet(() -> Result.empty(Reason.NO_ELIGIBLE_ACTIVITY));
    }

    private boolean sufficientOnly(RecommendationCandidate candidate, Map<UUID, Mastery> estimates) {
        return !candidate.topicIds().isEmpty() && candidate.topicIds().stream()
                .allMatch(topic -> estimates.containsKey(topic) && estimates.get(topic).level() == MasteryLevel.SUFFICIENT);
    }
}
