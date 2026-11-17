package vn.edu.hcmut.co3103.lms.bootstrap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import javax.sql.DataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ContentReportMigrationPostgresTest {
    @Autowired DataSource source;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshAndMainV21UpgradePreserveChecksumsAndReportConstraints(boolean upgrade) {
        var db = new JdbcTemplate(source);
        assertThat(db.queryForObject("SELECT version()", String.class)).contains("PostgreSQL 18");
        String schema = "reports_v22_" + UUID.randomUUID().toString().replace("-", "");
        var config = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema);
        try {
            config.target(upgrade ? "21" : "latest").load().migrate();
            String history = "SELECT version,script,checksum,success FROM " + schema
                    + ".flyway_schema_history WHERE version::integer <= 21 ORDER BY installed_rank";
            var before = db.queryForList(history);
            UUID reporter = UUID.randomUUID();
            db.update("INSERT INTO " + schema + ".lms_user_access(user_id,full_name,email,lms_role,access_status) "
                    + "VALUES (?,'Existing reporter','migration@example.test','STUDENT','ACTIVE')", reporter);
            var users = db.queryForList("SELECT * FROM " + schema + ".lms_user_access");
            var latest = config.target("latest").load();
            assertThat(latest.migrate().migrationsExecuted).isEqualTo(upgrade ? 1 : 0);
            latest.validate();
            assertThat(db.queryForList(history)).isEqualTo(before);
            assertThat(db.queryForList("SELECT * FROM " + schema + ".lms_user_access")).isEqualTo(users);
            assertThat(db.queryForObject("SELECT checksum FROM " + schema + ".flyway_schema_history WHERE version='22'", Integer.class))
                    .isEqualTo(420574687);
            assertThat(db.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname=? AND indexname LIKE 'idx_content_report_%'", Integer.class, schema))
                    .isEqualTo(3);
            UUID report = UUID.randomUUID(), target = UUID.randomUUID();
            String insert = "INSERT INTO " + schema + ".content_report(report_id,reporter_id,content_type,content_id,reason) VALUES (?,?,'DISCUSSION',?,'SPAM')";
            db.update(insert, report, reporter, target);
            assertThat(db.queryForMap("SELECT status,reviewed_by,reviewed_at FROM " + schema + ".content_report WHERE report_id=?", report))
                    .containsEntry("status", "PENDING").containsEntry("reviewed_by", null).containsEntry("reviewed_at", null);
            assertThatThrownBy(() -> db.update(insert, UUID.randomUUID(), reporter, target))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            for (String patch : java.util.List.of("status='REVIEWED'", "content_type='INVALID'", "reason='INVALID'"))
                assertThatThrownBy(() -> db.update("UPDATE " + schema + ".content_report SET " + patch + " WHERE report_id=?", report))
                        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update(insert, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            // Only this test's random private schema, never a persistent/shared deployment.
            db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
