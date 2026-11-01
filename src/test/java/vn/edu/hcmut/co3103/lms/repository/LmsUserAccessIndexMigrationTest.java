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
class LmsUserAccessIndexMigrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultListingIndexSupportsFreshAndUpgradedDatabases(boolean upgrade) {
        String schema = "user_index_" + UUID.randomUUID().toString().replace("-", "");
        var migration = Flyway.configure().dataSource(dataSource).schemas(schema);
        try {
            migration.target(upgrade ? "4" : "latest").load().migrate();
            String table = schema + ".lms_user_access";
            // Reverse insertion order and tie timestamps to exercise the UUID tiebreaker.
            jdbc.execute("""
                    INSERT INTO %s (user_id, full_name, email, lms_role, access_status, created_at, updated_at)
                    SELECT md5(i::text)::uuid, 'User ' || i, 'user' || i || '@example.test',
                           'STUDENT', 'ACTIVE', TIMESTAMPTZ '2020-01-01' + (i %% 100) * INTERVAL '1 second',
                           TIMESTAMPTZ '2020-01-01'
                    FROM generate_series(10005, 1, -1) AS i
                    """.formatted(table));
            jdbc.execute("ANALYZE " + table);
            String projection = "SELECT user_id, full_name, email, lms_role, access_status, last_login_at, created_at, updated_at FROM ";
            var before = jdbc.queryForList(projection + table + " ORDER BY created_at DESC, user_id ASC");
            String listing = projection + table + " ORDER BY created_at DESC, user_id ASC LIMIT 20";
            if (upgrade) {
                String plan = String.join("\n", jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + listing, String.class));
                System.out.println("Default listing before V5:\n" + plan);
                assertThat(plan).contains("Seq Scan", "Sort");
            }

            var flyway = migration.target("latest").load();
            flyway.migrate();
            flyway.validate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
            assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname = ? AND indexname = ?",
                    String.class, schema, "idx_lms_user_access_created_at_user_id"))
                    .contains("USING btree (created_at DESC, user_id)");
            assertThat(jdbc.queryForList(projection + table + " ORDER BY created_at DESC, user_id ASC"))
                    .isEqualTo(before);
            assertThat(jdbc.queryForList(listing)).isEqualTo(before.subList(0, 20));
            assertThat(jdbc.queryForList(listing + " OFFSET 20")).isEqualTo(before.subList(20, 40));

            String plan = String.join("\n", jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + listing, String.class));
            System.out.println("Default listing after V5 (upgrade=" + upgrade + "):\n" + plan);
            assertThat(plan).contains("Index Scan using idx_lms_user_access_created_at_user_id")
                    .doesNotContain("Sort", "Seq Scan");
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
