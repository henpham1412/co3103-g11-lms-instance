package vn.edu.hcmut.co3103.lms.bootstrap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Upgrade only the current-main migration set plus LMS-9 V17; unmerged PRs reserve no versions. */
class MasteryMigrationPostgresTest {
    @ParameterizedTest
    @ValueSource(ints = {13, 14})
    void upgradesCurrentMainBaselinesAndValidatesV17(int baseline) {
        var source = new DriverManagerDataSource(
                System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/lms_instance_dev"),
                System.getenv().getOrDefault("DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("DB_PASSWORD", ""));
        var db = new JdbcTemplate(source);
        assertThat(db.queryForObject("SELECT version()", String.class)).contains("PostgreSQL");
        String schema = "lms9_v17_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("CREATE SCHEMA " + schema);
        try {
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target(Integer.toString(baseline)).load().migrate();
            var checksums = db.queryForList("SELECT version,checksum FROM " + schema + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
            var upgraded = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load();
            upgraded.migrate(); upgraded.validate();
            assertThat(db.queryForList("SELECT version,checksum FROM " + schema + ".flyway_schema_history WHERE version::integer<=? ORDER BY installed_rank", baseline))
                    .isEqualTo(checksums);
            assertThat(db.queryForList("SELECT version FROM " + schema + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank", String.class))
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "17", "18", "19", "20", "21", "22", "23");
            assertThat(db.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_name IN ('learning_signal','learner_mastery')", String.class, schema))
                    .containsExactlyInAnyOrder("learning_signal", "learner_mastery");
            assertThat(db.queryForObject("SELECT numeric_scale IS NULL FROM information_schema.columns WHERE table_schema=? AND table_name='learner_mastery' AND column_name='mastery_score'", Boolean.class, schema)).isTrue();
        } finally {
            // Only this test's randomly named private schema, never the shared/public schema.
            db.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
