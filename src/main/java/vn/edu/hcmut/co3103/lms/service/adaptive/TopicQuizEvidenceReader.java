package vn.edu.hcmut.co3103.lms.service.adaptive;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal historical Quiz reader under the coordinator-authorized D-08 working
 * policy; this is not a claim of formal whole-group approval or an authorization
 * boundary. Intentionally not a Spring bean or wired into application workflows.
 *
 * Complete graded evidence survives later content hiding, class archival and
 * current membership/local-access changes. Current access must be enforced
 * independently by future callers. The canonical model has no invalidation or
 * cancellation flag; do not invent one. All source-validity predicates must run
 * BEFORE this query's LIMIT, not as a filter on its three returned candidates.
 * Submission time is canonical; UUID DESC is a provisional D-02 tie-break.
 */
public final class TopicQuizEvidenceReader {

    static final String LATEST_TOPIC_POINTS = """
            SELECT qa.attempt_id, qa.class_member_id, st.skill_topic_id, qa.submitted_at,
                   SUM(COALESCE(aa.points_earned, 0)) AS earned_points,
                   SUM(q.points_awarded) AS possible_points
            FROM quiz_attempt qa
            JOIN class_member cm ON cm.class_member_id = qa.class_member_id
            JOIN quiz quiz ON quiz.quiz_id = qa.quiz_id
            JOIN activity a ON a.activity_id = quiz.activity_id AND a.activity_type = 'QUIZ'
            JOIN lesson l ON l.lesson_id = a.lesson_id
            JOIN chapter ch ON ch.chapter_id = l.chapter_id
            JOIN question q ON q.quiz_id = quiz.quiz_id
            JOIN question_skill_topics qst ON qst.question_id = q.question_id
            JOIN skill_topics st ON st.skill_topic_id = qst.skill_topic_id
            LEFT JOIN attempt_answer aa ON aa.attempt_id = qa.attempt_id
                                       AND aa.question_id = q.question_id
            WHERE cm.class_member_id = :classMemberId
              AND cm.class_id = :classId
              AND ch.class_id = :classId
              AND st.class_id = :classId
              AND st.skill_topic_id = :skillTopicId
              AND qa.status = 'SUBMITTED'
              AND qa.score IS NOT NULL
              AND qa.graded_at IS NOT NULL
              AND qa.submitted_at IS NOT NULL
            GROUP BY qa.attempt_id, qa.class_member_id, st.skill_topic_id, qa.submitted_at
            HAVING SUM(q.points_awarded) > 0
            ORDER BY qa.submitted_at DESC, qa.attempt_id DESC
            LIMIT 3
            """;

    private final NamedParameterJdbcOperations jdbc;
    private final MasteryCalculator calculator;

    public TopicQuizEvidenceReader(NamedParameterJdbcOperations jdbc, MasteryCalculator calculator) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
    }

    /**
     * One statement snapshot, at most one result per requested member/topic/attempt.
     * Empty means no eligible historical evidence in this scope. Callers must supply trusted
     * scope IDs; this method does not establish permission to view learner data.
     * Missing answers are zero; stored points (including deductions) are not regraded.
     */
    public List<TopicQuizEvidence> readLatestGradedCandidates(UUID classId, UUID classMemberId,
                                                            UUID skillTopicId) {
        Objects.requireNonNull(classId, "classId");
        Objects.requireNonNull(classMemberId, "classMemberId");
        Objects.requireNonNull(skillTopicId, "skillTopicId");
        var parameters = Map.of("classId", classId, "classMemberId", classMemberId,
                "skillTopicId", skillTopicId);
        return List.copyOf(jdbc.query(LATEST_TOPIC_POINTS, parameters, (row, index) -> {
            BigDecimal score = calculator.calculateTopicScore(row.getBigDecimal("earned_points"),
                    row.getBigDecimal("possible_points")).orElseThrow();
            return new TopicQuizEvidence(row.getObject("attempt_id", UUID.class),
                    row.getObject("class_member_id", UUID.class),
                    row.getObject("skill_topic_id", UUID.class),
                    row.getObject("submitted_at", OffsetDateTime.class), score);
        }));
    }

    /** Internal calculated evidence value, not an API or persistence model. */
    public record TopicQuizEvidence(UUID attemptId, UUID classMemberId, UUID skillTopicId,
                                    OffsetDateTime submittedAt, BigDecimal topicScore) {
    }
}
