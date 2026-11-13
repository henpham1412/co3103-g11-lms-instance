package vn.edu.hcmut.co3103.lms.service.adaptive.recommendation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.adaptive.LearnerMasteryRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.service.classmanagement.ClassMembershipService;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.UUID;

/** Internal learner-specific selection boundary. Future APIs must supply the authenticated subject. */
@Service
@RequiredArgsConstructor
public class RecommendationService {
    private final LmsUserAccessRepository users;
    private final ClassRepository classes;
    private final ClassMemberRepository members;
    private final ClassMembershipService membership;
    private final LearnerMasteryRepository masteries;
    private final EligibleActivityReader activities;
    private final TopicPrerequisiteResolver prerequisites;
    private final RecommendationRanker ranker;

    /** One read-only PostgreSQL snapshot across candidates, mappings, history, graph and mastery. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecommendationRanker.Result recommend(UUID classId, UUID studentId) {
        var user = users.findById(studentId).filter(u -> u.getAccessStatus() == AccessStatus.ACTIVE
                        && u.getLmsRole() == LmsRole.STUDENT)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN"));
        var course = classes.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CLASS_NOT_FOUND"));
        membership.requireActiveMembership(classId, user.getUserId());
        var member = members.findByClassIdAndStudentId(classId, studentId).orElseThrow();
        if (course.getStatus() != Class.ClassStatus.ACTIVE)
            return RecommendationRanker.Result.empty(RecommendationRanker.Reason.CLASS_ARCHIVED);

        var eligible = activities.read(classId, member.getClassMemberId(), OffsetDateTime.now());
        if (eligible.isEmpty()) return RecommendationRanker.Result.empty(RecommendationRanker.Reason.NO_ELIGIBLE_ACTIVITY);
        var graph = prerequisites.read(classId);
        var estimates = new HashMap<UUID, RecommendationRanker.Mastery>();
        masteries.findByClassMemberId(member.getClassMemberId()).stream()
                .filter(m -> graph.topics().contains(m.getSkillTopicId()))
                .forEach(m -> estimates.put(m.getSkillTopicId(), new RecommendationRanker.Mastery(m.getMasteryLevel(), m.getMasteryScore())));
        return ranker.select(eligible, estimates, graph);
    }
}
