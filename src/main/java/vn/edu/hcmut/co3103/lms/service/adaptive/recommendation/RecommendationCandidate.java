package vn.edu.hcmut.co3103.lms.service.adaptive.recommendation;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Internal prepared data, not a public API DTO or an Activity completion state. */
public record RecommendationCandidate(UUID activityId, UUID quizId, String title, UUID lessonId,
                                      int chapterOrder, int lessonOrder, int activityOrder,
                                      Set<UUID> topicIds, boolean lessonCompleted, boolean hasSubmittedAttempt) {
    public RecommendationCandidate {
        Objects.requireNonNull(activityId);
        Objects.requireNonNull(quizId);
        Objects.requireNonNull(lessonId);
        topicIds = Set.copyOf(topicIds);
    }

    // Compare unsigned UUID bytes, consistently with PostgreSQL UUID ordering.
    public static final Comparator<RecommendationCandidate> SEQUENCE = Comparator
            .comparingInt(RecommendationCandidate::chapterOrder)
            .thenComparingInt(RecommendationCandidate::lessonOrder)
            .thenComparingInt(RecommendationCandidate::activityOrder)
            .thenComparing(candidate -> candidate.activityId().toString());
}
