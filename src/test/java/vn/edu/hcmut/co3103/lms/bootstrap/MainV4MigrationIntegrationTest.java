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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MainV4MigrationIntegrationTest {
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void upgradesOfficialMainV4WithoutChangingClassMembershipUserSetupOrHistory() {
        String schema = "main_v4_" + UUID.randomUUID().toString().replace("-", "");
        UUID admin = UUID.randomUUID();
        UUID tutor = UUID.randomUUID();
        UUID student = UUID.randomUUID();
        UUID classId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        var configuration = Flyway.configure().dataSource(dataSource).schemas(schema);
        try {
            var main = configuration.target("4").load();
            main.migrate();
            main.validate();
            assertThat(main.info().current().getScript()).isEqualTo("V4__create_classes_and_memberships.sql");
            assertThat(main.info().current().getChecksum()).isEqualTo(-1197899662);
            for (var user : List.of(admin, tutor, student)) {
                jdbc.update("INSERT INTO " + schema + ".lms_user_access "
                                + "(user_id, full_name, email, lms_role, access_status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                        user, "Existing user", "shared@example.test",
                        user.equals(admin) ? "ADMINISTRATOR" : user.equals(tutor) ? "TUTOR" : "STUDENT");
            }
            jdbc.update("UPDATE " + schema + ".lms_setup_state SET is_completed = TRUE, "
                    + "completed_at = CURRENT_TIMESTAMP, bootstrapped_by = ? WHERE id = 'GLOBAL'", admin);
            jdbc.update("INSERT INTO " + schema + ".class "
                            + "(class_id, tutor_id, class_code, title, max_capacity, enrollment_policy) "
                            + "VALUES (?, ?, 'V4-CLASS', 'Existing class', 5, 'OPEN')", classId, tutor);
            jdbc.update("INSERT INTO " + schema + ".class_member "
                            + "(class_member_id, class_id, student_id, status, joined_at) "
                            + "VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)", memberId, classId, student);

            String userProjection = "SELECT user_id, full_name, email, lms_role, access_status, "
                    + "last_login_at, created_at, updated_at FROM " + schema + ".lms_user_access ORDER BY user_id";
            var usersBefore = jdbc.queryForList(userProjection);
            var classesBefore = jdbc.queryForList("SELECT * FROM " + schema + ".class");
            var membersBefore = jdbc.queryForList("SELECT * FROM " + schema + ".class_member");
            var setupBefore = jdbc.queryForList("SELECT * FROM " + schema + ".lms_setup_state");
            String history = "SELECT version, script, checksum, success FROM " + schema
                    + ".flyway_schema_history WHERE version IN ('1', '2', '3', '4') ORDER BY installed_rank";
            var historyBefore = jdbc.queryForList(history);

            var latest = configuration.target("latest").load();
            latest.migrate();
            latest.validate();
            assertThat(latest.info().applied()).filteredOn(info -> info.getVersion() != null)
                    .extracting(info -> info.getVersion().getVersion())
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
            assertThat(jdbc.queryForList(history)).isEqualTo(historyBefore);
            assertThat(jdbc.queryForList(userProjection)).isEqualTo(usersBefore);
            assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".class")).isEqualTo(classesBefore);
            assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".class_member")).isEqualTo(membersBefore);
            assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".lms_setup_state")).isEqualTo(setupBefore);
            assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname = ? "
                            + "AND indexname = 'idx_lms_user_access_created_at_user_id'", String.class, schema))
                    .contains("created_at DESC, user_id");
            assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                            + "WHERE table_schema = ? AND table_name = 'lms_user_access' AND data_type = 'numeric' "
                            + "AND numeric_precision = 21 AND numeric_scale = 9", String.class, schema))
                    .containsExactlyInAnyOrder("last_synced_profile_iat", "last_login_token_iat");
            BigDecimal issuance = new BigDecimal("1893456000.123456789");
            jdbc.update("UPDATE " + schema + ".lms_user_access SET last_synced_profile_iat = ?, "
                    + "last_login_token_iat = ? WHERE user_id = ?", issuance, issuance, tutor);
            assertThat(jdbc.queryForObject("SELECT last_synced_profile_iat FROM " + schema
                    + ".lms_user_access WHERE user_id = ?", BigDecimal.class, tutor)).isEqualTo(issuance);
            assertThat(jdbc.queryForObject("SELECT last_login_token_iat FROM " + schema
                    + ".lms_user_access WHERE user_id = ?", BigDecimal.class, tutor)).isEqualTo(issuance);

            String auditInsert = "INSERT INTO " + schema + ".audit_logs "
                    + "(audit_log_id, actor_id, action, entity_type, entity_id, details) "
                    + "VALUES (?, ?, 'UPDATE_USER_ACCESS', 'USER', ?, '{}')";
            assertThatThrownBy(() -> jdbc.update(auditInsert, UUID.randomUUID(), UUID.randomUUID(), student))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update(auditInsert, UUID.randomUUID(), admin, student);
            var auditBefore = jdbc.queryForList("SELECT * FROM " + schema + ".audit_logs");
            assertThatThrownBy(() -> jdbc.update("DELETE FROM " + schema + ".lms_user_access WHERE user_id = ?", admin))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(jdbc.queryForObject("SELECT delete_rule FROM information_schema.referential_constraints "
                            + "WHERE constraint_schema = ? AND constraint_name = 'fk_audit_logs_actor'", String.class, schema))
                    .isEqualTo("RESTRICT");
            latest.migrate();
            latest.validate();
            assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".audit_logs")).isEqualTo(auditBefore);
            assertThat(jdbc.queryForList("SELECT * FROM " + schema + ".lms_setup_state")).isEqualTo(setupBefore);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
