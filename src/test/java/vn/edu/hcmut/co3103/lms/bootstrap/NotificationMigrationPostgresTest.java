package vn.edu.hcmut.co3103.lms.bootstrap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationMigrationPostgresTest {
    @Autowired DataSource dataSource;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshInstallAndV22UpgradeKeepExistingDataAndEnforceNotificationConstraints(boolean upgrade) {
        var db = new JdbcTemplate(dataSource);
        String schema = "notification_v23_" + UUID.randomUUID().toString().replace("-", "");
        var config = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema);
        try {
            if (upgrade) config.target("22").load().migrate();
            else config.load().migrate();
            String history = "SELECT version, script, checksum FROM " + schema
                    + ".flyway_schema_history WHERE version::integer <= 22 ORDER BY installed_rank";
            var baseline = db.queryForList(history);
            UUID user = UUID.randomUUID();
            UUID reportId = UUID.randomUUID();
            db.update("INSERT INTO " + schema + ".lms_user_access (user_id, full_name, email, lms_role, access_status)"
                    + " VALUES (?, 'Existing student', ?, 'STUDENT', 'ACTIVE')", user, user + "@example.test");
            db.update("INSERT INTO " + schema + ".content_report (report_id, reporter_id, content_type, content_id, reason)"
                    + " VALUES (?, ?, 'DISCUSSION', ?, 'SPAM')", reportId, user, UUID.randomUUID());

            var latest = config.target("latest").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(upgrade ? 1 : 0);
            latest.validate();
            assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("23");
            assertThat(db.queryForList(history)).isEqualTo(baseline);
            assertThat(db.queryForObject("SELECT reporter_id FROM " + schema + ".content_report WHERE report_id = ?",
                    UUID.class, reportId)).isEqualTo(user);

            String table = schema + ".notification";
            assertThat(db.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
            String insert = "INSERT INTO " + table
                    + " (recipient_id, notification_type, title, message, source_event_key)"
                    + " VALUES (?, 'CONTENT_RELEASED', 'New content', 'Available', 'release')";
            db.update(insert, user);
            assertThat(db.queryForMap("SELECT is_read, read_at, target_url FROM " + table))
                    .containsEntry("is_read", false).containsEntry("read_at", null).containsEntry("target_url", null);
            assertThatThrownBy(() -> db.update(insert, user)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update(insert, UUID.randomUUID()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("UPDATE " + table + " SET is_read = TRUE"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("UPDATE " + table + " SET read_at = CURRENT_TIMESTAMP"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(db.update("UPDATE " + table + " SET is_read = TRUE, read_at = CURRENT_TIMESTAMP")).isEqualTo(1);
        } finally {
            db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
