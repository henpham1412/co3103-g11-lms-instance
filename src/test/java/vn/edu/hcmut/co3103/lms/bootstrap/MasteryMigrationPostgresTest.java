package vn.edu.hcmut.co3103.lms.bootstrap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** PR #31 fixtures are exact files from 30a62a05906828261c105584582ad375b4abf552. */
class MasteryMigrationPostgresTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void upgradesV13AndValidatesWithOrWithoutThePendingLms13MigrationSet(boolean withLms13) {
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
                    .locations("classpath:db/migration").target("13").load().migrate();
            var checksums = db.queryForList("SELECT version,checksum FROM " + schema + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
            String[] locations = withLms13
                    ? new String[]{"classpath:db/migration", "classpath:adaptive/lms13-30a62a0"}
                    : new String[]{"classpath:db/migration"};
            var upgraded = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                    .locations(locations).load();
            upgraded.migrate(); upgraded.validate();
            assertThat(db.queryForList("SELECT version,checksum FROM " + schema + ".flyway_schema_history WHERE version::integer<=13 ORDER BY installed_rank"))
                    .isEqualTo(checksums);
            assertThat(db.queryForObject("SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE version IS NOT NULL", Integer.class))
                    .isEqualTo(withLms13 ? 17 : 14);
            assertThat(db.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_name IN ('learning_signal','learner_mastery')", String.class, schema))
                    .containsExactlyInAnyOrder("learning_signal", "learner_mastery");
            assertThat(db.queryForObject("SELECT numeric_scale IS NULL FROM information_schema.columns WHERE table_schema=? AND table_name='learner_mastery' AND column_name='mastery_score'", Boolean.class, schema)).isTrue();
        } finally {
            // Only this test's randomly named private schema, never the shared/public schema.
            db.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
