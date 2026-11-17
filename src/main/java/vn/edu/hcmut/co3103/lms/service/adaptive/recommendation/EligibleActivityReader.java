package vn.edu.hcmut.co3103.lms.service.adaptive.recommendation;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import vn.edu.hcmut.co3103.lms.service.assessment.QuizAttemptEligibility;
import vn.edu.hcmut.co3103.lms.service.assessment.QuizQuestionRules;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

/** Scalar, batched LMS-4/5/7/8 reads. No mutation, grading, or Tutor management calls. */
@Component
public class EligibleActivityReader {
    private final NamedParameterJdbcTemplate jdbc;
    public EligibleActivityReader(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<RecommendationCandidate> read(UUID classId, UUID memberId, OffsetDateTime now) {
        var params = new HashMap<String, Object>(Map.of("classId", classId, "memberId", memberId));
        List<QuizRow> rows = jdbc.query("""
                SELECT a.activity_id,a.title,l.lesson_id,ch.sequence_order chapter_order,
                       l.sequence_order lesson_order,a.sequence_order activity_order,
                       q.quiz_id,q.time_limit_minutes,q.attempts_allowed,q.opening_time,q.deadline,
                       EXISTS(SELECT 1 FROM completion_mark mark
                              WHERE mark.class_member_id=cm.class_member_id AND mark.lesson_id=l.lesson_id) lesson_completed
                FROM activity a JOIN lesson l ON l.lesson_id=a.lesson_id
                JOIN chapter ch ON ch.chapter_id=l.chapter_id JOIN class c ON c.class_id=ch.class_id
                JOIN class_member cm ON cm.class_id=c.class_id AND cm.class_member_id=:memberId
                JOIN lms_user_access u ON u.user_id=cm.student_id
                JOIN quiz q ON q.activity_id=a.activity_id
                WHERE c.class_id=:classId AND c.status='ACTIVE' AND cm.status='ACTIVE'
                  AND u.access_status='ACTIVE' AND u.lms_role='STUDENT'
                  AND ch.status='PUBLISHED' AND l.status='PUBLISHED' AND a.status='PUBLISHED'
                  AND a.activity_type='QUIZ'
                """, params, (r, n) -> new QuizRow(r.getObject("activity_id", UUID.class), r.getString("title"),
                r.getObject("lesson_id", UUID.class), r.getInt("chapter_order"), r.getInt("lesson_order"),
                r.getInt("activity_order"), r.getObject("quiz_id", UUID.class),
                new QuizAttemptEligibility.Window(r.getInt("time_limit_minutes"), r.getInt("attempts_allowed"),
                        r.getObject("opening_time", OffsetDateTime.class), r.getObject("deadline", OffsetDateTime.class)),
                r.getBoolean("lesson_completed")));
        // Only new actions are selected here. Discard closed windows before loading
        // their definitions/history; LMS-7 remains responsible for existing resumes.
        rows = rows.stream().filter(row -> QuizAttemptEligibility.evaluate(row.window(), List.of(), true, now)
                == QuizAttemptEligibility.Action.START).toList();
        if (rows.isEmpty()) return List.of();
        params.put("quizzes", rows.stream().map(QuizRow::quizId).toList());
        params.put("activities", rows.stream().map(QuizRow::activityId).toList());

        Map<UUID, QuestionDefinition> questions = new HashMap<>();
        jdbc.query("""
                SELECT q.question_id,q.quiz_id,q.question_type,q.content,q.points_awarded,q.points_deducted,
                       o.option_id,o.content option_content,o.is_correct,o.order_index option_order
                FROM question q LEFT JOIN question_option o ON o.question_id=q.question_id
                WHERE q.quiz_id IN (:quizzes)
                """, params, (org.springframework.jdbc.core.RowCallbackHandler) r -> {
            UUID id = r.getObject("question_id", UUID.class);
            QuestionDefinition definition = questions.get(id);
            if (definition == null) {
                definition = new QuestionDefinition(r.getObject("quiz_id", UUID.class), r.getString("question_type"),
                        r.getString("content"), r.getBigDecimal("points_awarded"), r.getBigDecimal("points_deducted"));
                questions.put(id, definition);
            }
            if (r.getObject("option_id") != null) {
                definition.optionCount++;
                if (r.getBoolean("is_correct")) definition.correctCount++;
                String content = r.getString("option_content");
                int order = r.getInt("option_order");
                definition.optionsValid &= content != null && !content.isBlank() && order >= 0
                        && definition.orders.add(order);
            }
        });
        Map<UUID, Boolean> validQuizzes = new HashMap<>();
        questions.values().forEach(q -> validQuizzes.merge(q.quizId, q.valid(), Boolean::logicalAnd));

        Map<UUID, List<QuizAttemptEligibility.Attempt>> attempts = new HashMap<>();
        jdbc.query("""
                SELECT quiz_id,status,started_at FROM quiz_attempt
                WHERE class_member_id=:memberId AND quiz_id IN (:quizzes) ORDER BY attempt_number
                """, params, (org.springframework.jdbc.core.RowCallbackHandler) r -> attempts
                .computeIfAbsent(r.getObject("quiz_id", UUID.class), ignored -> new ArrayList<>())
                .add(new QuizAttemptEligibility.Attempt(r.getString("status"), r.getObject("started_at", OffsetDateTime.class))));

        Map<UUID, Set<UUID>> topics = new HashMap<>();
        jdbc.query("""
                SELECT m.activity_id,st.skill_topic_id FROM activity_skill_topics m
                JOIN skill_topics st ON st.skill_topic_id=m.skill_topic_id AND st.class_id=:classId
                WHERE m.activity_id IN (:activities)
                UNION
                SELECT qz.activity_id,st.skill_topic_id FROM quiz qz JOIN question q ON q.quiz_id=qz.quiz_id
                JOIN question_skill_topics m ON m.question_id=q.question_id
                JOIN skill_topics st ON st.skill_topic_id=m.skill_topic_id AND st.class_id=:classId
                WHERE qz.quiz_id IN (:quizzes)
                """, params, (org.springframework.jdbc.core.RowCallbackHandler) r -> topics
                .computeIfAbsent(r.getObject("activity_id", UUID.class), ignored -> new HashSet<>())
                .add(r.getObject("skill_topic_id", UUID.class)));

        List<RecommendationCandidate> result = new ArrayList<>();
        for (QuizRow row : rows) {
            var history = attempts.getOrDefault(row.quizId(), List.of());
            if (!validQuizzes.getOrDefault(row.quizId(), false)) continue;
            // RESUME belongs exclusively to LMS-7/LMS-5 orchestration, not this selector.
            if (QuizAttemptEligibility.evaluate(row.window(), history, true, now) != QuizAttemptEligibility.Action.START) continue;
            result.add(new RecommendationCandidate(row.activityId(), row.quizId(), row.title(), row.lessonId(),
                    row.chapterOrder(), row.lessonOrder(), row.activityOrder(),
                    topics.getOrDefault(row.activityId(), Set.of()), row.completed(),
                    history.stream().anyMatch(a -> "SUBMITTED".equals(a.status()))));
        }
        return List.copyOf(result);
    }

    private record QuizRow(UUID activityId, String title, UUID lessonId, int chapterOrder, int lessonOrder,
                           int activityOrder, UUID quizId, QuizAttemptEligibility.Window window, boolean completed) { }

    private static final class QuestionDefinition {
        final UUID quizId;
        final String type, content;
        final BigDecimal awarded, deducted;
        final Set<Integer> orders = new HashSet<>();
        int optionCount, correctCount;
        boolean optionsValid = true;
        QuestionDefinition(UUID quizId, String type, String content, BigDecimal awarded, BigDecimal deducted) {
            this.quizId = quizId; this.type = type; this.content = content; this.awarded = awarded; this.deducted = deducted;
        }
        boolean valid() {
            return optionsValid && QuizQuestionRules.validDefinition(type, content, awarded, deducted, optionCount, correctCount);
        }
    }
}
