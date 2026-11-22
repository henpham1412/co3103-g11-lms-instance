package vn.edu.hcmut.co3103.lms.repository.notification;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static vn.edu.hcmut.co3103.lms.repository.notification.NotificationRepository.IGNORE_DUPLICATE;
import static vn.edu.hcmut.co3103.lms.repository.notification.NotificationRepository.INSERT;

@Repository
public class NotificationDeliveryRepository {
    private static final String LESSON_URL = "'/classes/' || l.class_id || '/lessons/' || l.lesson_id";

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationDeliveryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void send(UUID recipient, String type, String title, String message, String url,
                     String source, OffsetDateTime now) {
        jdbc.update(INSERT + """
                VALUES (:recipient, :type, :title, :message, :url, :source, :now)
                """ + IGNORE_DUPLICATE, Map.of("recipient", recipient, "type", type, "title", title,
                "message", message, "url", url, "source", source, "now", now));
    }

    public void notifySubmission(UUID classId, UUID activityId, String source, OffsetDateTime now) {
        jdbc.update(INSERT + """
                SELECT c.tutor_id, 'ASSIGNMENT_SUBMISSION', 'New assignment submission',
                    'A student submitted work for an assignment.',
                    '/tutor/classes/' || c.class_id || '/content/assignment/' || :activityId || '/grade',
                    :source, :now
                FROM class c WHERE c.class_id = :classId
                """ + IGNORE_DUPLICATE, Map.of("classId", classId, "activityId", activityId,
                "source", source, "now", now));
    }

    public enum ContentScope {
        CHAPTER("ch.chapter_id = :id", "TRUE", "TRUE"),
        LESSON("l.lesson_id = :id", "TRUE", "TRUE"),
        MATERIAL("TRUE", "m.material_id = :id", "FALSE"),
        ACTIVITY("TRUE", "FALSE", "a.activity_id = :id");

        private final String lessonFilter;
        private final String materialFilter;
        private final String activityFilter;

        ContentScope(String lessonFilter, String materialFilter, String activityFilter) {
            this.lessonFilter = lessonFilter;
            this.materialFilter = materialFilter;
            this.activityFilter = activityFilter;
        }
    }

    public void notifyContent(ContentScope scope, UUID id, OffsetDateTime now) {
        jdbc.update("""
                WITH visible_lessons AS NOT MATERIALIZED (
                    SELECT l.lesson_id, c.class_id
                    FROM lesson l
                    JOIN chapter ch ON ch.chapter_id = l.chapter_id
                    JOIN class c ON c.class_id = ch.class_id
                    WHERE c.status = 'ACTIVE' AND ch.status = 'PUBLISHED' AND l.status = 'PUBLISHED' AND %s
                ),
                released AS (
                    SELECT l.class_id, m.title, %s AS target_url,
                        'material:' || m.material_id AS resource_key
                    FROM material m JOIN visible_lessons l ON l.lesson_id = m.lesson_id
                    WHERE m.status = 'PUBLISHED' AND %s
                    UNION ALL
                    SELECT l.class_id, a.title, %s || '/activities/' || a.activity_id,
                        'activity:' || a.activity_id
                    FROM activity a JOIN visible_lessons l ON l.lesson_id = a.lesson_id
                    WHERE a.status = 'PUBLISHED' AND %s
                      AND ((a.activity_type = 'ASSIGNMENT' AND EXISTS (
                            SELECT 1 FROM assignment s WHERE s.activity_id = a.activity_id))
                        OR (a.activity_type = 'QUIZ' AND EXISTS (
                            SELECT 1 FROM quiz q JOIN question qq ON qq.quiz_id = q.quiz_id
                            WHERE q.activity_id = a.activity_id)))
                )
                """.formatted(scope.lessonFilter, LESSON_URL, scope.materialFilter, LESSON_URL, scope.activityFilter)
                + INSERT + """
                SELECT cm.student_id, 'CONTENT_RELEASED', 'New learning content',
                    'Now available: ' || r.title, r.target_url, r.resource_key, :now
                FROM released r
                JOIN class_member cm ON cm.class_id = r.class_id AND cm.status = 'ACTIVE'
                JOIN lms_user_access u ON u.user_id = cm.student_id
                WHERE u.lms_role = 'STUDENT' AND u.access_status = 'ACTIVE'
                ORDER BY cm.student_id, r.resource_key
                """ + IGNORE_DUPLICATE, Map.of("id", id, "now", now));
    }

    public void notifyReply(UUID replyId, OffsetDateTime now) {
        jdbc.update("""
                WITH RECURSIVE ancestors AS (
                    SELECT discussion_id, parent_id, class_id, lesson_id FROM discussion WHERE discussion_id = :reply
                    UNION
                    SELECT p.discussion_id, p.parent_id, p.class_id, p.lesson_id
                    FROM ancestors a JOIN discussion p ON p.discussion_id = a.parent_id
                      AND p.class_id = a.class_id AND p.lesson_id IS NOT DISTINCT FROM a.lesson_id
                ),
                thread AS (
                    SELECT d.discussion_id, d.author_id, d.class_id, d.lesson_id
                    FROM ancestors a JOIN discussion d ON d.discussion_id = a.discussion_id
                    WHERE a.parent_id IS NULL
                    UNION
                    SELECT d.discussion_id, d.author_id, d.class_id, d.lesson_id
                    FROM thread t JOIN discussion d ON d.parent_id = t.discussion_id
                      AND d.class_id = t.class_id AND d.lesson_id IS NOT DISTINCT FROM t.lesson_id
                )
                """ + INSERT + """
                SELECT DISTINCT u.user_id, 'DISCUSSION_REPLY', 'New discussion reply',
                    'A discussion you participated in has a new reply.',
                    CASE WHEN u.lms_role = 'TUTOR' THEN '/tutor/discussion' ELSE '/discussion' END,
                    'reply:' || r.discussion_id, :now
                FROM thread t
                JOIN discussion r ON r.discussion_id = :reply
                JOIN class c ON c.class_id = t.class_id
                JOIN lms_user_access u ON u.user_id = t.author_id
                WHERE u.user_id <> r.author_id AND u.access_status = 'ACTIVE'
                  AND ((u.lms_role = 'TUTOR' AND u.user_id = c.tutor_id)
                    OR (u.lms_role = 'STUDENT' AND EXISTS (
                        SELECT 1 FROM class_member cm WHERE cm.class_id = t.class_id
                          AND cm.student_id = u.user_id AND cm.status = 'ACTIVE')))
                """ + IGNORE_DUPLICATE, Map.of("reply", replyId, "now", now));
    }
}
