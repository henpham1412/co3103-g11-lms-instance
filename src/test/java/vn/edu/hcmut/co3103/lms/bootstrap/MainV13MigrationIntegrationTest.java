package vn.edu.hcmut.co3103.lms.bootstrap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MainV13MigrationIntegrationTest {
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void upgradesCanonicalMainWithoutChangingLearningProgressTopicsOrHistory() {
        String schema = "main_v13_" + UUID.randomUUID().toString().replace("-", "");
        UUID admin = UUID.randomUUID();
        UUID tutor = UUID.randomUUID();
        UUID student = UUID.randomUUID();
        UUID classId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID lessonId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        UUID topicId = UUID.randomUUID();
        UUID prerequisiteId = UUID.randomUUID();
        var configuration = Flyway.configure().dataSource(dataSource).schemas(schema);
        try {
            var main = configuration.target("13").load();
            main.migrate();
            main.validate();
            assertThat(main.info().current().getScript()).isEqualTo("V13__create_question_skill_topics.sql");
            assertThat(main.info().current().getChecksum()).isEqualTo(-415992144);
            for (UUID user : List.of(admin, tutor, student)) {
                jdbc.update("INSERT INTO " + schema + ".lms_user_access "
                                + "(user_id, full_name, email, lms_role, access_status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                        user, "Existing user", "shared@example.test",
                        user.equals(admin) ? "ADMINISTRATOR" : user.equals(tutor) ? "TUTOR" : "STUDENT");
            }
            jdbc.update("UPDATE " + schema + ".lms_setup_state SET is_completed = TRUE, "
                    + "completed_at = CURRENT_TIMESTAMP, bootstrapped_by = ? WHERE id = 'GLOBAL'", admin);
            jdbc.update("INSERT INTO " + schema + ".class "
                            + "(class_id, tutor_id, class_code, title, max_capacity, enrollment_policy) "
                            + "VALUES (?, ?, 'V13-CLASS', 'Existing class', 5, 'OPEN')", classId, tutor);
            jdbc.update("INSERT INTO " + schema + ".class_member "
                            + "(class_member_id, class_id, student_id, status, joined_at) "
                            + "VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)", memberId, classId, student);
            jdbc.update("INSERT INTO " + schema + ".chapter (chapter_id, class_id, title, sequence_order) "
                    + "VALUES (?, ?, 'Existing chapter', 0)", chapterId, classId);
            jdbc.update("INSERT INTO " + schema + ".lesson (lesson_id, chapter_id, title, sequence_order) "
                    + "VALUES (?, ?, 'Existing lesson', 0)", lessonId, chapterId);
            jdbc.update("INSERT INTO " + schema + ".activity "
                    + "(activity_id, lesson_id, title, activity_type, sequence_order) "
                    + "VALUES (?, ?, 'Existing quiz', 'QUIZ', 0)", activityId, lessonId);
            jdbc.update("INSERT INTO " + schema + ".quiz (quiz_id, activity_id, time_limit_minutes, deadline) "
                    + "VALUES (?, ?, 30, TIMESTAMPTZ '2030-01-01')", quizId, activityId);
            jdbc.update("INSERT INTO " + schema + ".question "
                    + "(question_id, quiz_id, question_type, content, points_awarded, order_index) "
                    + "VALUES (?, ?, 'TRUE_FALSE', 'Existing question', 10, 0)", questionId, quizId);
            jdbc.update("INSERT INTO " + schema + ".completion_mark (class_member_id, lesson_id) VALUES (?, ?)",
                    memberId, lessonId);
            jdbc.update("INSERT INTO " + schema + ".last_visit (class_member_id, lesson_id) VALUES (?, ?)",
                    memberId, lessonId);
            jdbc.update("INSERT INTO " + schema + ".skill_topics (skill_topic_id, class_id, name, difficulty_level) "
                    + "VALUES (?, ?, 'Advanced', 5), (?, ?, 'Foundation', 1)", topicId, classId, prerequisiteId, classId);
            jdbc.update("INSERT INTO " + schema + ".skill_prerequisites "
                    + "(skill_topic_id, prerequisite_skill_topic_id) VALUES (?, ?)", topicId, prerequisiteId);
            jdbc.update("INSERT INTO " + schema + ".lesson_skill_topics VALUES (?, ?)", lessonId, topicId);
            jdbc.update("INSERT INTO " + schema + ".activity_skill_topics VALUES (?, ?)", activityId, topicId);
            jdbc.update("INSERT INTO " + schema + ".question_skill_topics VALUES (?, ?)", questionId, topicId);

            String users = "SELECT user_id, full_name, email, lms_role, access_status, last_login_at, "
                    + "created_at, updated_at FROM " + schema + ".lms_user_access ORDER BY user_id";
            var usersBefore = jdbc.queryForList(users);
            List<String> existingTables = List.of("class", "class_member", "lms_setup_state", "chapter", "lesson",
                    "activity", "quiz", "question", "completion_mark", "last_visit", "skill_topics",
                    "skill_prerequisites", "lesson_skill_topics", "activity_skill_topics", "question_skill_topics");
            var rowsBefore = snapshot(schema, existingTables);
            assertThat(rowsBefore.values()).allSatisfy(rows -> assertThat(rows).isNotEmpty());
            String history = "SELECT version, script, checksum, success FROM " + schema
                    + ".flyway_schema_history WHERE version::integer <= 13 ORDER BY installed_rank";
            var historyBefore = jdbc.queryForList(history);
            assertThat(historyBefore).hasSize(13);

            var latest = configuration.target("latest").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(3);
            latest.validate();
            assertThat(latest.info().applied()).filteredOn(info -> info.getVersion() != null)
                    .extracting(info -> info.getVersion().getVersion())
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13",
                            "14", "15", "16");
            assertThat(jdbc.queryForList(history)).isEqualTo(historyBefore);
            assertThat(jdbc.queryForList(users)).isEqualTo(usersBefore);
            assertThat(snapshot(schema, existingTables)).isEqualTo(rowsBefore);
            assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname = ? "
                            + "AND indexname = 'idx_lms_user_access_created_at_user_id'", String.class, schema))
                    .contains("created_at DESC, user_id");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + schema + ".lms_user_access "
                    + "WHERE last_synced_profile_iat IS NULL AND last_login_token_iat IS NULL", Integer.class))
                    .isEqualTo(3);
            assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema = ? AND table_name = 'lms_user_access' AND data_type = 'numeric' "
                            + "AND numeric_precision = 21 AND numeric_scale = 9 AND is_nullable = 'YES'",
                            String.class, schema))
                    .containsExactlyInAnyOrder("last_synced_profile_iat", "last_login_token_iat");
            BigDecimal issuance = new BigDecimal("1893456000.123456789");
            jdbc.update("UPDATE " + schema + ".lms_user_access SET last_synced_profile_iat = ?, "
                    + "last_login_token_iat = ? WHERE user_id = ?", issuance, issuance, tutor);
            for (String column : List.of("last_synced_profile_iat", "last_login_token_iat")) {
                assertThat(jdbc.queryForObject("SELECT " + column + " FROM " + schema
                        + ".lms_user_access WHERE user_id = ?", BigDecimal.class, tutor)).isEqualTo(issuance);
            }
            String auditInsert = "INSERT INTO " + schema + ".audit_logs "
                    + "(audit_log_id, actor_id, action, entity_type, entity_id, details) "
                    + "VALUES (?, ?, 'UPDATE_USER_ACCESS', 'USER', ?, '{}')";
            assertThatThrownBy(() -> jdbc.update(auditInsert, UUID.randomUUID(), UUID.randomUUID(), student))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update(auditInsert, UUID.randomUUID(), admin, student);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM " + schema + ".lms_user_access WHERE user_id = ?", admin))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT delete_rule FROM information_schema.referential_constraints "
                            + "WHERE constraint_schema = ? AND constraint_name = 'fk_audit_logs_actor'", String.class, schema))
                    .isEqualTo("RESTRICT");

            var additionsBeforeRestart = snapshot(schema, List.of("lms_user_access", "audit_logs", "flyway_schema_history"));
            assertThat(latest.migrate().migrationsExecuted).isZero();
            latest.validate();
            assertThat(snapshot(schema, existingTables)).isEqualTo(rowsBefore);
            assertThat(snapshot(schema, List.of("lms_user_access", "audit_logs", "flyway_schema_history")))
                    .isEqualTo(additionsBeforeRestart);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private Map<String, List<Map<String, Object>>> snapshot(String schema, List<String> tables) {
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>();
        for (String table : tables) {
            rows.put(table, jdbc.queryForList("SELECT * FROM " + schema + "." + table + " ORDER BY 1, 2"));
        }
        return rows;
    }
}
