package vn.edu.hcmut.co3103.lms.service.adaptive.recommendation;

import org.junit.jupiter.api.Test;
import vn.edu.hcmut.co3103.lms.enums.MasteryLevel;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillPrerequisiteValidator.PrerequisiteEdge;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static vn.edu.hcmut.co3103.lms.enums.MasteryLevel.*;
import static vn.edu.hcmut.co3103.lms.service.adaptive.recommendation.RecommendationRanker.*;

class RecommendationRankerTest {
    final RecommendationRanker ranker = new RecommendationRanker();
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();

    @Test void weakPrecedesDevelopingEvenWhenDevelopingIsAPrerequisite() {
        var weak = candidate(2, b); var developing = candidate(0, a);
        var result = ranker.select(List.of(developing, weak), Map.of(a, mastery(DEVELOPING, "50"), b, mastery(WEAK, "49")), graph(edge(b, a)));
        assertThat(result.activity()).isEqualTo(weak); assertThat(result.reason()).isEqualTo(Reason.ADDITIONAL_PRACTICE);
    }

    @Test void lowerFullPrecisionScorePrecedesSequenceWithinSamePriority() {
        var first = candidate(0, a); var lower = candidate(2, b);
        assertThat(select(List.of(first, lower), Map.of(a, mastery(WEAK, "49.9999"), b, mastery(WEAK, "49.9998"))).activity()).isEqualTo(lower);
    }

    @Test void prerequisitePrecedesDependentEvenWithHigherScoreAndLaterSequence() {
        var dependent = candidate(0, b); var prerequisite = candidate(2, a);
        assertThat(ranker.select(List.of(dependent, prerequisite), Map.of(a, mastery(WEAK, "40"), b, mastery(WEAK, "1")), graph(edge(b, a))).activity()).isEqualTo(prerequisite);
    }

    @Test void transitivePrerequisiteIsConsideredWhenIntermediateHasNoPractice() {
        var dependent = candidate(0, c); var ancestor = candidate(3, a);
        assertThat(ranker.select(List.of(dependent, ancestor), Map.of(a, mastery(DEVELOPING, "70"), c, mastery(DEVELOPING, "50")), graph(edge(c, b), edge(b, a))).activity()).isEqualTo(ancestor);
    }

    @Test void unavailablePrerequisiteDoesNotBlockDependentPractice() {
        var dependent = candidate(0, b);
        assertThat(ranker.select(List.of(dependent), Map.of(a, mastery(WEAK, "0"), b, mastery(WEAK, "10")), graph(edge(b, a))).activity()).isEqualTo(dependent);
    }

    @Test void sufficientTopicYieldsToSubsequentLearningEvenIfEarlierQuizWasNeverAttempted() {
        var sufficient = candidate(0, a); var next = candidate(1, b);
        var result = select(List.of(sufficient, next), Map.of(a, mastery(SUFFICIENT, "80")));
        assertThat(result.activity()).isEqualTo(next); assertThat(result.reason()).isEqualTo(Reason.NORMAL_SEQUENCE);
    }

    @Test void equalPriorityUsesHierarchyThenActivitySequence() {
        var later = candidate(4, a); var earlier = candidate(2, b);
        assertThat(select(List.of(later, earlier), Map.of(a, mastery(WEAK, "10"), b, mastery(WEAK, "10"))).activity()).isEqualTo(earlier);
        var nextChapter = new RecommendationCandidate(UUID.randomUUID(), UUID.randomUUID(), "Next", UUID.randomUUID(), 1, 0, 0, Set.of(a), false, false);
        assertThat(select(List.of(nextChapter, later), Map.of()).activity()).isEqualTo(later);
    }

    @Test void finalUuidTieBreakUsesUnsignedPostgresCompatibleOrderRegardlessOfInputOrder() {
        var low = withId(candidate(0, a), UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"));
        var high = withId(candidate(0, a), UUID.fromString("80000000-0000-0000-0000-000000000000"));
        assertThat(select(List.of(high, low), Map.of(a, mastery(WEAK, "10"))).activity()).isEqualTo(low);
        assertThat(select(List.of(low, high), Map.of()).activity()).isEqualTo(low);
    }

    @Test void multiTopicActivityAppearsOnceAndUsesItsHighestPriorityRelevantNeed() {
        var multi = new RecommendationCandidate(UUID.randomUUID(), UUID.randomUUID(), "Multi", UUID.randomUUID(), 0, 0, 1, Set.of(a, b), false, false);
        var result = select(List.of(multi), Map.of(a, mastery(DEVELOPING, "60"), b, mastery(WEAK, "0")));
        assertThat(result.activity()).isEqualTo(multi); assertThat(result.reinforcedTopicId()).isEqualTo(b);
    }

    @Test void missingMasteryIsNotWeakButAnActualZeroIsWeak() {
        var noEvidence = candidate(0, a); var zero = candidate(1, b);
        assertThat(select(List.of(noEvidence, zero), Map.of(b, mastery(WEAK, "0"))).activity()).isEqualTo(zero);
        assertThat(select(List.of(noEvidence, zero), Map.of()).activity()).isEqualTo(noEvidence);
    }

    @Test void noPracticeForKnownNeedFallsBackWithoutInventingTopicRelevance() {
        var normal = candidate(0, b);
        var result = select(List.of(normal), Map.of(a, mastery(WEAK, "0")));
        assertThat(result.activity()).isEqualTo(normal); assertThat(result.reason()).isEqualTo(Reason.NORMAL_SEQUENCE);
        assertThat(result.reinforcedTopicId()).isNull();
    }

    @Test void noCandidateHasExplicitEmptyReason() {
        var result = select(List.of(), Map.of());
        assertThat(result.activity()).isNull(); assertThat(result.reason()).isEqualTo(Reason.NO_ELIGIBLE_ACTIVITY);
    }

    @Test void lessonCompletionIsASequenceHintAndDoesNotMakeActivitiesComplete() {
        var earlier = candidate(0, a);
        var completed = new RecommendationCandidate(earlier.activityId(), earlier.quizId(), earlier.title(), earlier.lessonId(), 0, 0, 0, Set.of(a), true, false);
        var next = candidate(1, b);
        assertThat(select(List.of(completed, next), Map.of()).activity()).isEqualTo(next);
        assertThat(select(List.of(completed), Map.of()).activity()).isEqualTo(completed);
        assertThat(select(List.of(completed, next), Map.of(a, mastery(WEAK, "0"))).activity()).isEqualTo(completed);
    }

    @Test void permittedSubmittedQuizIsRetainedForReinforcementButNewActionsLeadFallback() {
        var old = candidate(0, a);
        var retry = new RecommendationCandidate(old.activityId(), old.quizId(), old.title(), old.lessonId(), 0, 0, 0, Set.of(a), false, true);
        var fresh = candidate(1, b);
        assertThat(select(List.of(retry, fresh), Map.of()).activity()).isEqualTo(fresh);
        assertThat(select(List.of(retry), Map.of()).activity()).isEqualTo(retry);
        assertThat(select(List.of(retry, fresh), Map.of(a, mastery(WEAK, "0"))).activity()).isEqualTo(retry);
    }

    @Test void diamondDagTraversalTerminatesAndInvalidCyclesAreRejected() {
        var dag = graph(edge(c, a), edge(c, b), edge(b, a));
        assertThat(dag.ancestors(c)).containsExactlyInAnyOrder(a, b);
        assertThatThrownBy(() -> graph(edge(a, b), edge(b, a))).isInstanceOf(vn.edu.hcmut.co3103.lms.exception.AppException.class);
    }

    private Result select(List<RecommendationCandidate> candidates, Map<UUID, Mastery> mastery) {
        return ranker.select(candidates, mastery, graph());
    }
    private TopicPrerequisiteResolver.Graph graph(PrerequisiteEdge... edges) { return new TopicPrerequisiteResolver.Graph(Set.of(a, b, c), Set.of(edges)); }
    private PrerequisiteEdge edge(UUID dependent, UUID prerequisite) { return new PrerequisiteEdge(dependent, prerequisite); }
    private Mastery mastery(MasteryLevel level, String score) { return new Mastery(level, new BigDecimal(score)); }
    private RecommendationCandidate candidate(int order, UUID topic) { return new RecommendationCandidate(UUID.randomUUID(), UUID.randomUUID(), "Quiz", UUID.randomUUID(), 0, 0, order, Set.of(topic), false, false); }
    private RecommendationCandidate withId(RecommendationCandidate c, UUID id) { return new RecommendationCandidate(id, c.quizId(), c.title(), c.lessonId(), c.chapterOrder(), c.lessonOrder(), c.activityOrder(), c.topicIds(), c.lessonCompleted(), c.hasSubmittedAttempt()); }
}
