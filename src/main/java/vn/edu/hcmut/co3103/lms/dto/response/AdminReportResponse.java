package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Map;

/**
 * LMS-14 report envelope (DOC-05 §5.4). {@code message} is present only when the requested scope has no
 * reportable records; {@code data} always carries zero-valued counts so clients never need special cases.
 */
public record AdminReportResponse<T>(
        @JsonProperty("report_type") String reportType,
        Map<String, Object> filters,
        @JsonInclude(JsonInclude.Include.NON_NULL) String message,
        T data
) {
    public static final String NO_REPORTABLE_DATA = "No reportable data is available.";

    /**
     * Current membership counts. {@code active_members}, {@code pending_requests} and {@code inactive_members}
     * are the DOC-05 §5.4 fields; {@code total_memberships} is the sum of all four membership states and the
     * class counts describe the classes in scope (including classes without memberships).
     */
    public record Enrollment(
            @JsonProperty("active_members") long activeMembers,
            @JsonProperty("pending_requests") long pendingRequests,
            @JsonProperty("inactive_members") long inactiveMembers,
            @JsonProperty("rejected_requests") long rejectedRequests,
            @JsonProperty("total_memberships") long totalMemberships,
            @JsonProperty("total_classes") long totalClasses,
            @JsonProperty("active_classes") long activeClasses,
            @JsonProperty("archived_classes") long archivedClasses
    ) { }

    /**
     * Current lesson-based completion (LMS-7 rules): only ACTIVE memberships and Lessons that are PUBLISHED
     * under a PUBLISHED Chapter count. A membership is completed only when its class has at least one eligible
     * Lesson and every eligible Lesson is marked complete. Percentages are 0-100, scale 2, HALF_UP, computed
     * from exact ratios; both are {@code 0.00} when there are no ACTIVE memberships.
     */
    public record Completion(
            @JsonProperty("active_members") long activeMembers,
            @JsonProperty("eligible_lessons") long eligibleLessons,
            @JsonProperty("completed_lesson_marks") long completedLessonMarks,
            @JsonProperty("completed_memberships") long completedMemberships,
            @JsonProperty("in_progress_memberships") long inProgressMemberships,
            @JsonProperty("completion_rate") BigDecimal completionRate,
            @JsonProperty("average_progress_percentage") BigDecimal averageProgressPercentage
    ) { }

    /**
     * Attempt-level Quiz and submission-level Assignment statistics. Multiple attempts by one student are
     * counted individually; averages are not per-student final grades. {@code average_assessment_score} is
     * weighted by the total number of graded records across both QuizAttempts and Assignment Submissions
     * (not an unweighted average of the two category averages). All averages use graded records only,
     * include valid {@code 0.00} scores, use scale 2 HALF_UP rounding, and are {@code null} when no graded
     * record exists.
     */
    public record Assessment(
            @JsonProperty("total_quiz_attempts") long totalQuizAttempts,
            @JsonProperty("in_progress_quiz_attempts") long inProgressQuizAttempts,
            @JsonProperty("submitted_quiz_attempts") long submittedQuizAttempts,
            @JsonProperty("graded_quiz_attempts") long gradedQuizAttempts,
            @JsonProperty("average_quiz_attempt_score") BigDecimal averageQuizAttemptScore,
            @JsonProperty("total_assignment_submissions") long totalAssignmentSubmissions,
            @JsonProperty("ungraded_assignment_submissions") long ungradedAssignmentSubmissions,
            @JsonProperty("graded_assignment_submissions") long gradedAssignmentSubmissions,
            @JsonProperty("late_assignment_submissions") long lateAssignmentSubmissions,
            @JsonProperty("average_assignment_submission_score") BigDecimal averageAssignmentSubmissionScore,
            @JsonProperty("average_assessment_score") BigDecimal averageAssessmentScore
    ) { }
}
