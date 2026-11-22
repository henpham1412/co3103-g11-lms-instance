package vn.edu.hcmut.co3103.lms.repository.admin;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.UUID;

/**
 * Read-only, set-based aggregates for LMS-14 over the canonical tables. A null {@code classId} means all classes.
 */
@Repository
public class AdminReportRepository {

    private static final String SCOPED_CLASS = """
            scoped_class AS (
                SELECT class_id, status FROM class
                WHERE CAST(:classId AS uuid) IS NULL OR class_id = CAST(:classId AS uuid)
            )
            """;

    private static final String ENROLLMENT = "WITH " + SCOPED_CLASS + """
            SELECT
                (SELECT COUNT(*) FROM scoped_class) AS total_classes,
                (SELECT COUNT(*) FROM scoped_class WHERE status = 'ACTIVE') AS active_classes,
                (SELECT COUNT(*) FROM scoped_class WHERE status = 'ARCHIVED') AS archived_classes,
                COUNT(*) FILTER (WHERE cm.status = 'ACTIVE') AS active_members,
                COUNT(*) FILTER (WHERE cm.status = 'PENDING') AS pending_requests,
                COUNT(*) FILTER (WHERE cm.status = 'INACTIVE') AS inactive_members,
                COUNT(*) FILTER (WHERE cm.status = 'REJECTED') AS rejected_requests
            FROM class_member cm
            JOIN scoped_class sc ON sc.class_id = cm.class_id
            """;

    private static final String COMPLETION = "WITH " + SCOPED_CLASS + """
            , eligible_lesson AS (
                SELECT l.lesson_id, ch.class_id
                FROM lesson l
                JOIN chapter ch ON ch.chapter_id = l.chapter_id
                JOIN scoped_class sc ON sc.class_id = ch.class_id
                WHERE ch.status = 'PUBLISHED' AND l.status = 'PUBLISHED'
            ),
            class_lessons AS (
                SELECT class_id, COUNT(*) AS eligible FROM eligible_lesson GROUP BY class_id
            ),
            member_progress AS (
                SELECT cm.class_member_id,
                       COALESCE(cl.eligible, 0) AS eligible,
                       (SELECT COUNT(*) FROM completion_mark m
                        JOIN eligible_lesson el ON el.lesson_id = m.lesson_id AND el.class_id = cm.class_id
                        WHERE m.class_member_id = cm.class_member_id) AS completed
                FROM class_member cm
                JOIN scoped_class sc ON sc.class_id = cm.class_id
                LEFT JOIN class_lessons cl ON cl.class_id = cm.class_id
                WHERE cm.status = 'ACTIVE'
            )
            SELECT
                (SELECT COUNT(*) FROM member_progress) AS active_members,
                (SELECT COUNT(*) FROM eligible_lesson) AS eligible_lessons,
                (SELECT COALESCE(SUM(completed), 0) FROM member_progress) AS completed_lesson_marks,
                (SELECT COUNT(*) FROM member_progress WHERE eligible > 0 AND completed = eligible)
                    AS completed_memberships,
                (SELECT COALESCE(SUM(CASE WHEN eligible = 0 THEN 0
                                          ELSE completed * 100.0 / eligible END), 0)
                 FROM member_progress) AS progress_percentage_sum
            """;

    private static final String ASSESSMENT = "WITH " + SCOPED_CLASS + """
            , attempt AS (
                SELECT qa.status, qa.score, qa.graded_at
                FROM quiz_attempt qa
                JOIN class_member cm ON cm.class_member_id = qa.class_member_id
                JOIN scoped_class sc ON sc.class_id = cm.class_id
            ),
            graded_attempt AS (
                SELECT score FROM attempt
                WHERE status = 'SUBMITTED' AND score IS NOT NULL AND graded_at IS NOT NULL
            ),
            sub AS (
                SELECT s.status, s.score, s.graded_at, s.is_late
                FROM submission s
                JOIN class_member cm ON cm.class_member_id = s.class_member_id
                JOIN scoped_class sc ON sc.class_id = cm.class_id
            ),
            graded_sub AS (
                SELECT score FROM sub
                WHERE status = 'GRADED' AND score IS NOT NULL AND graded_at IS NOT NULL
            )
            SELECT
                (SELECT COUNT(*) FROM attempt) AS total_quiz_attempts,
                (SELECT COUNT(*) FROM attempt WHERE status = 'IN_PROGRESS') AS in_progress_quiz_attempts,
                (SELECT COUNT(*) FROM attempt WHERE status = 'SUBMITTED') AS submitted_quiz_attempts,
                (SELECT COUNT(*) FROM graded_attempt) AS graded_quiz_attempts,
                (SELECT AVG(score) FROM graded_attempt) AS average_quiz_attempt_score,
                (SELECT COUNT(*) FROM sub) AS total_assignment_submissions,
                (SELECT COUNT(*) FROM sub WHERE status = 'SUBMITTED') AS ungraded_assignment_submissions,
                (SELECT COUNT(*) FROM graded_sub) AS graded_assignment_submissions,
                (SELECT COUNT(*) FROM sub WHERE is_late) AS late_assignment_submissions,
                (SELECT AVG(score) FROM graded_sub) AS average_assignment_submission_score,
                (SELECT AVG(score) FROM (
                    SELECT score FROM graded_attempt
                    UNION ALL
                    SELECT score FROM graded_sub
                ) combined_graded) AS average_assessment_score
            """;

    public record EnrollmentCounts(long totalClasses, long activeClasses, long archivedClasses,
                                   long activeMembers, long pendingRequests, long inactiveMembers,
                                   long rejectedRequests) { }

    /** {@code progressPercentageSum} is the exact sum of per-ACTIVE-membership progress percentages (0-100). */
    public record CompletionCounts(long activeMembers, long eligibleLessons, long completedLessonMarks,
                                   long completedMemberships, BigDecimal progressPercentageSum) { }

    /** Averages are unrounded and null when there is no graded record of that kind. */
    public record AssessmentCounts(long totalQuizAttempts, long inProgressQuizAttempts, long submittedQuizAttempts,
                                   long gradedQuizAttempts, BigDecimal averageQuizAttemptScore,
                                   long totalAssignmentSubmissions, long ungradedAssignmentSubmissions,
                                   long gradedAssignmentSubmissions, long lateAssignmentSubmissions,
                                   BigDecimal averageAssignmentSubmissionScore,
                                   BigDecimal averageAssessmentScore) { }

    private final NamedParameterJdbcTemplate jdbc;

    public AdminReportRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public EnrollmentCounts enrollment(UUID classId) {
        return jdbc.queryForObject(ENROLLMENT, scope(classId), (rs, row) -> new EnrollmentCounts(
                rs.getLong("total_classes"), rs.getLong("active_classes"), rs.getLong("archived_classes"),
                rs.getLong("active_members"), rs.getLong("pending_requests"), rs.getLong("inactive_members"),
                rs.getLong("rejected_requests")));
    }

    public CompletionCounts completion(UUID classId) {
        return jdbc.queryForObject(COMPLETION, scope(classId), (rs, row) -> new CompletionCounts(
                rs.getLong("active_members"), rs.getLong("eligible_lessons"), rs.getLong("completed_lesson_marks"),
                rs.getLong("completed_memberships"), rs.getBigDecimal("progress_percentage_sum")));
    }

    public AssessmentCounts assessment(UUID classId) {
        return jdbc.queryForObject(ASSESSMENT, scope(classId), (rs, row) -> new AssessmentCounts(
                rs.getLong("total_quiz_attempts"), rs.getLong("in_progress_quiz_attempts"),
                rs.getLong("submitted_quiz_attempts"), rs.getLong("graded_quiz_attempts"),
                rs.getBigDecimal("average_quiz_attempt_score"),
                rs.getLong("total_assignment_submissions"), rs.getLong("ungraded_assignment_submissions"),
                rs.getLong("graded_assignment_submissions"), rs.getLong("late_assignment_submissions"),
                rs.getBigDecimal("average_assignment_submission_score"),
                rs.getBigDecimal("average_assessment_score")));
    }

    private static MapSqlParameterSource scope(UUID classId) {
        return new MapSqlParameterSource().addValue("classId", classId == null ? null : classId.toString(),
                Types.VARCHAR);
    }
}
