package vn.edu.hcmut.co3103.lms.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class IdentitySyncWatermarkMigrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesExistingDataAndInitializesUnknownIssuanceOnFreshAndUpgradedDatabases(boolean upgrade) {
        String schema = "identity_sync_" + UUID.randomUUID().toString().replace("-", "");
        var migration = Flyway.configure().dataSource(dataSource).schemas(schema);
        try {
            migration.target(upgrade ? "5" : "latest").load().migrate();
            String table = schema + ".lms_user_access";
            jdbc.execute("""
                    INSERT INTO %s (user_id, full_name, email, lms_role, access_status,
                                    last_login_at, created_at, updated_at)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'Existing User', 'same@example.test',
                            'TUTOR', 'SUSPENDED', TIMESTAMPTZ '2025-01-01', TIMESTAMPTZ '2024-01-01', TIMESTAMPTZ '2025-01-02'),
                           ('00000000-0000-0000-0000-000000000002', 'Another User', 'same@example.test',
                            'ADMINISTRATOR', 'ACTIVE', NULL, TIMESTAMPTZ '2024-02-01', TIMESTAMPTZ '2025-02-01')
                    """.formatted(table));
            String originalColumns = "SELECT user_id, full_name, email, lms_role, access_status, last_login_at, "
                    + "created_at, updated_at FROM " + table + " ORDER BY user_id";
            var before = jdbc.queryForList(originalColumns);

            var flyway = migration.target("latest").load();
            flyway.migrate();
            flyway.validate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
            assertThat(jdbc.queryForList(originalColumns)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                    + " WHERE last_synced_profile_iat IS NULL AND last_login_token_iat IS NULL", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'lms_user_access'
                        AND data_type = 'numeric' AND numeric_precision = 21 AND numeric_scale = 9
                        AND is_nullable = 'YES'
                    """, String.class, schema)).containsExactlyInAnyOrder("last_synced_profile_iat", "last_login_token_iat");
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
