package vn.edu.hcmut.co3103.lms.service.adaptive;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.enums.MasteryLevel;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.service.classmanagement.ClassMembershipService;

import java.math.BigDecimal;
import java.util.*;

/** One current-mastery query shared by API mapping and LMS-7 Tutor Progress. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MasteryReadService {
    private final NamedParameterJdbcTemplate jdbc;
    private final LmsUserAccessRepository users;
    private final ClassMemberRepository members;
    private final ClassMembershipService membership;

    public record TopicMastery(UUID skillTopicId, String name, BigDecimal score, MasteryLevel level) {
        public boolean evidenceAvailable() { return score != null; }
    }

    public List<TopicMastery> readForTutor(UUID classId, UUID memberId, UUID tutorId) {
        users.findById(tutorId).filter(u -> u.getAccessStatus() == AccessStatus.ACTIVE && u.getLmsRole() == LmsRole.TUTOR)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN"));
        membership.requireOwningTutorRead(classId, tutorId);
        members.findByClassMemberIdAndClassId(memberId, classId)
                .filter(m -> m.getStatus() == ClassMember.MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CLASS_OR_MEMBER_NOT_FOUND"));
        return read(classId, memberId, null);
    }

    /** Called only after canonical recommendation authorization; expose selected candidate topics only. */
    List<TopicMastery> readForCandidate(UUID classId, UUID studentId, Set<UUID> topicIds) {
        if (topicIds.isEmpty()) return List.of();
        UUID memberId = members.findByClassIdAndStudentId(classId, studentId)
                .filter(m -> m.getStatus() == ClassMember.MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "MEMBERSHIP_REQUIRED"))
                .getClassMemberId();
        return read(classId, memberId, topicIds);
    }

    private List<TopicMastery> read(UUID classId, UUID memberId, Set<UUID> selectedTopics) {
        String query = """
                SELECT st.skill_topic_id,st.name,lm.mastery_score,lm.mastery_level
                FROM skill_topics st JOIN class_member cm ON cm.class_id=st.class_id
                LEFT JOIN learner_mastery lm ON lm.skill_topic_id=st.skill_topic_id
                     AND lm.class_member_id=cm.class_member_id
                WHERE st.class_id=:classId AND cm.class_member_id=:memberId AND cm.status='ACTIVE'
                """;
        Map<String, Object> params = new HashMap<>(Map.of("classId", classId, "memberId", memberId));
        if (selectedTopics != null) {
            query += " AND st.skill_topic_id IN (:topics)\n";
            params.put("topics", selectedTopics);
        }
        query += " ORDER BY st.name,st.skill_topic_id";
        return List.copyOf(jdbc.query(query, params, (row, n) -> new TopicMastery(
                row.getObject("skill_topic_id", UUID.class), row.getString("name"),
                row.getBigDecimal("mastery_score"), row.getString("mastery_level") == null
                        ? null : MasteryLevel.valueOf(row.getString("mastery_level")))));
    }
}
