package vn.edu.hcmut.co3103.lms.repository.notification;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Map;

import static vn.edu.hcmut.co3103.lms.repository.notification.NotificationRepository.IGNORE_DUPLICATE;
import static vn.edu.hcmut.co3103.lms.repository.notification.NotificationRepository.INSERT;

@Repository
public class DeadlineReminderRepository {
    private static final String CANDIDATES = """
            WITH upcoming AS (
                SELECT activity_id, assignment_id AS assessment_id, 'assignment' AS kind, deadline, opening_time,
                    NULL AS time_limit_minutes
                FROM assignment WHERE deadline > :now AND deadline <= :until
                UNION ALL
                SELECT activity_id, quiz_id, 'quiz', deadline, opening_time, time_limit_minutes
                FROM quiz WHERE deadline > :now AND deadline <= :until
            ),
            eligible AS (
                SELECT cm.student_id, a.title,
                    '/classes/' || c.class_id || '/lessons/' || l.lesson_id || '/activities/' || a.activity_id AS url,
                    d.kind || ':' || d.assessment_id || ':' || EXTRACT(epoch FROM d.deadline) AS source_key
                FROM upcoming d
                JOIN activity a ON a.activity_id = d.activity_id
                JOIN lesson l ON l.lesson_id = a.lesson_id
                JOIN chapter ch ON ch.chapter_id = l.chapter_id
                JOIN class c ON c.class_id = ch.class_id
                JOIN class_member cm ON cm.class_id = c.class_id AND cm.status = 'ACTIVE'
                JOIN lms_user_access u ON u.user_id = cm.student_id
                WHERE c.status = 'ACTIVE' AND ch.status = 'PUBLISHED' AND l.status = 'PUBLISHED'
                  AND a.status = 'PUBLISHED' AND a.activity_type = upper(d.kind)
                  AND u.lms_role = 'STUDENT' AND u.access_status = 'ACTIVE'
                  AND (
                    (d.kind = 'assignment' AND (d.opening_time IS NULL OR d.opening_time <= :now)
                        AND NOT EXISTS (SELECT 1 FROM submission s WHERE s.assignment_id = d.assessment_id
                                          AND s.class_member_id = cm.class_member_id))
                    OR
                    (d.kind = 'quiz'
                        AND EXISTS (SELECT 1 FROM question q WHERE q.quiz_id = d.assessment_id)
                        AND NOT EXISTS (
                            SELECT 1 FROM quiz_attempt qa
                            WHERE qa.quiz_id = d.assessment_id AND qa.class_member_id = cm.class_member_id
                              AND (qa.status = 'SUBMITTED'
                                OR qa.started_at + make_interval(mins => d.time_limit_minutes) <= :now))
                        AND ((d.opening_time IS NULL OR d.opening_time <= :now) OR EXISTS (
                            SELECT 1 FROM quiz_attempt qa
                            WHERE qa.quiz_id = d.assessment_id AND qa.class_member_id = cm.class_member_id
                              AND qa.status = 'IN_PROGRESS')))
                  )
            )
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DeadlineReminderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int sendUpcoming(OffsetDateTime now, OffsetDateTime until) {
        // Completed work is excluded first. The only quiz attempts left are resumable,
        // so a learner with no attempt still needs the opening-time check.
        return jdbc.update(CANDIDATES + INSERT + """
                SELECT e.student_id, 'DEADLINE_REMINDER', 'Upcoming deadline',
                    'Due within 24 hours: ' || e.title, e.url, e.source_key, :now
                FROM eligible e
                WHERE NOT EXISTS (
                    SELECT 1 FROM notification n WHERE n.recipient_id = e.student_id
                      AND n.notification_type = 'DEADLINE_REMINDER' AND n.source_event_key = e.source_key)
                ORDER BY e.student_id, e.source_key
                """ + IGNORE_DUPLICATE, Map.of("now", now, "until", until));
    }
}
