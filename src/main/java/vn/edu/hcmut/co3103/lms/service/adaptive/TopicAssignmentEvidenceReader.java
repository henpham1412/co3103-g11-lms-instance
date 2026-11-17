package vn.edu.hcmut.co3103.lms.service.adaptive;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Internal historical evidence, not an authorization boundary. Canonical scores are percentages. */
public final class TopicAssignmentEvidenceReader {
    private static final String ALL = """
            SELECT s.submission_id, s.class_member_id, st.skill_topic_id, s.submitted_at, s.score
            FROM submission s
            JOIN class_member cm ON cm.class_member_id=s.class_member_id
            JOIN assignment assignment ON assignment.assignment_id=s.assignment_id
            JOIN activity a ON a.activity_id=assignment.activity_id AND a.activity_type='ASSIGNMENT'
            JOIN lesson l ON l.lesson_id=a.lesson_id
            JOIN chapter ch ON ch.chapter_id=l.chapter_id
            JOIN skill_topics st ON st.skill_topic_id=:skillTopicId AND st.class_id=:classId
            WHERE cm.class_member_id=:classMemberId AND cm.class_id=:classId AND ch.class_id=:classId
              AND s.status='GRADED' AND s.score IS NOT NULL AND s.score BETWEEN 0 AND 100
              AND s.graded_at IS NOT NULL AND s.submitted_at IS NOT NULL
              AND EXISTS (SELECT 1 FROM activity_skill_topics mapping
                          WHERE mapping.activity_id=a.activity_id AND mapping.skill_topic_id=st.skill_topic_id)
            ORDER BY s.submitted_at DESC, s.submission_id DESC
            """;
    private final NamedParameterJdbcOperations jdbc;

    public TopicAssignmentEvidenceReader(NamedParameterJdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public List<TopicAssignmentEvidence> readAllGradedCandidates(UUID classId, UUID classMemberId, UUID skillTopicId) {
        return read(ALL, classId, classMemberId, skillTopicId);
    }

    public List<TopicAssignmentEvidence> readLatestGradedCandidates(UUID classId, UUID classMemberId, UUID skillTopicId) {
        return read(ALL + "LIMIT 3\n", classId, classMemberId, skillTopicId);
    }

    private List<TopicAssignmentEvidence> read(String sql, UUID classId, UUID memberId, UUID topicId) {
        var params = Map.of("classId", Objects.requireNonNull(classId, "classId"),
                "classMemberId", Objects.requireNonNull(memberId, "classMemberId"),
                "skillTopicId", Objects.requireNonNull(topicId, "skillTopicId"));
        return List.copyOf(jdbc.query(sql, params, (row, index) -> new TopicAssignmentEvidence(
                row.getObject("submission_id", UUID.class), row.getObject("class_member_id", UUID.class),
                row.getObject("skill_topic_id", UUID.class), row.getObject("submitted_at", OffsetDateTime.class),
                row.getBigDecimal("score").setScale(2, RoundingMode.HALF_UP))));
    }

    public record TopicAssignmentEvidence(UUID submissionId, UUID classMemberId, UUID skillTopicId,
                                          OffsetDateTime submittedAt, BigDecimal topicScore) { }
}
