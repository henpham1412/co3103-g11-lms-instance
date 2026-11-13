package vn.edu.hcmut.co3103.lms.service.adaptive;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.dto.response.adaptive.RecommendedActivitiesResponse;
import vn.edu.hcmut.co3103.lms.dto.response.adaptive.TutorMasteryResponse;
import vn.edu.hcmut.co3103.lms.service.adaptive.recommendation.RecommendationService;

import java.util.UUID;

/** Maps approved REST shapes within the same read snapshot as selection/authorization. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdaptiveLearningReadService {
    private final RecommendationService recommendations;
    private final MasteryReadService mastery;

    public RecommendedActivitiesResponse recommendedActivities(UUID classId, UUID studentId) {
        var result = recommendations.recommend(classId, studentId);
        if (result.activity() == null) return new RecommendedActivitiesResponse(classId, null, result.reason().name());
        var candidate = result.activity();
        var topics = mastery.readForCandidate(classId, studentId, candidate.topicIds()).stream()
                .map(t -> new RecommendedActivitiesResponse.Topic(t.skillTopicId(), t.name(), t.level())).toList();
        return new RecommendedActivitiesResponse(classId, new RecommendedActivitiesResponse.Recommendation(
                candidate.activityId(), "QUIZ", candidate.title(), result.reason().name(), topics), null);
    }

    public TutorMasteryResponse tutorMastery(UUID classId, UUID memberId, UUID tutorId) {
        var items = mastery.readForTutor(classId, memberId, tutorId).stream().map(t ->
                new TutorMasteryResponse.Item(t.skillTopicId(), t.name(), t.score(), t.level(), t.evidenceAvailable())).toList();
        return new TutorMasteryResponse(classId, memberId, items);
    }
}
