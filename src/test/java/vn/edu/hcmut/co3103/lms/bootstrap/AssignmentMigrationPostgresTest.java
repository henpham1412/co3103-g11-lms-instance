package vn.edu.hcmut.co3103.lms.bootstrap;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Current PR35 upgrade and explicit unresolved collision with PR31 at 30a62a0. Private test schemas only. */
class AssignmentMigrationPostgresTest {
    private final DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("DB_URL"), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
    private final JdbcTemplate db = new JdbcTemplate(source);
    @Test void upgradesV13WithoutChangingEarlierChecksumsOrCanonicalTables() {
        String schema = "lms6_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("CREATE SCHEMA " + schema);
        try {
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("13").load().migrate();
            var before = db.queryForList("SELECT version,checksum FROM " + schema + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
            var upgraded = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load();
            upgraded.migrate(); upgraded.validate();
            assertThat(db.queryForList("SELECT version,checksum FROM " + schema + ".flyway_schema_history WHERE version::integer<=13 ORDER BY installed_rank")).isEqualTo(before);
            assertThat(db.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_name IN ('assignment','assignment_attachment','submission')", String.class, schema))
                    .containsExactlyInAnyOrder("assignment", "assignment_attachment", "submission");
            assertThat(db.queryForObject("SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE version IS NOT NULL", Integer.class)).isEqualTo(14);
        } finally { db.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }
    @Test void combiningThePendingV14AllocationsIsExplicitlyBlocked() {
        String schema = "lms6_collision_" + UUID.randomUUID().toString().replace("-", "");
        try {
            var combined = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration", "classpath:assignment/lms13-30a62a0").load();
            assertThatThrownBy(combined::migrate).isInstanceOf(FlywayException.class).hasMessageContaining("more than one migration with version 14");
        } finally { db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    }
}
