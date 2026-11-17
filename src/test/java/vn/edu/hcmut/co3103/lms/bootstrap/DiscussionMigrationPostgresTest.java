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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fresh install and upgrade from the actual merged main V20, in private disposable schemas. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DiscussionMigrationPostgresTest {
    // Reuse the application's PostgreSQL diagnostic-privacy settings, including for intentional
    // constraint failures. A standalone JDBC datasource would bypass that connection setup.
    @Autowired private DataSource source;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void freshAndMainUpgradePreserveHistoryDataAndDiscussionConstraints(boolean upgrade) {
        var db = new JdbcTemplate(source);
        assertThat(db.queryForObject("SELECT version()", String.class)).contains("PostgreSQL 18");
        String schema = "discussion_v21_" + UUID.randomUUID().toString().replace("-", "");
        var configuration = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration");
        try {
            if (upgrade) configuration.target("20").load().migrate();
            else configuration.load().migrate();
            var historyBefore = db.queryForList("SELECT version, script, checksum, success FROM " + schema
                    + ".flyway_schema_history WHERE version::integer <= 20 ORDER BY installed_rank");
            UUID author = UUID.randomUUID();
            UUID classId = UUID.randomUUID();
            UUID chapter = UUID.randomUUID();
            UUID lesson = UUID.randomUUID();
            db.update("INSERT INTO " + schema + ".lms_user_access(user_id,full_name,email,lms_role,access_status) "
                    + "VALUES (?, 'Existing Tutor', 'discussion-migration@example.test', 'TUTOR', 'ACTIVE')", author);
            db.update("INSERT INTO " + schema + ".class(class_id,tutor_id,class_code,title,enrollment_policy,status) "
                    + "VALUES (?, ?, ?, 'Existing class', 'OPEN', 'ACTIVE')", classId, author, "DM" + classId.toString().substring(0, 8));
            db.update("INSERT INTO " + schema + ".chapter(chapter_id,class_id,title,sequence_order) VALUES (?,?,'Existing chapter',1)", chapter, classId);
            db.update("INSERT INTO " + schema + ".lesson(lesson_id,chapter_id,title,sequence_order) VALUES (?,?,'Existing lesson',1)", lesson, chapter);
            var users = db.queryForList("SELECT * FROM " + schema + ".lms_user_access");
            var classes = db.queryForList("SELECT * FROM " + schema + ".class");
            var lessons = db.queryForList("SELECT * FROM " + schema + ".lesson");

            var latest = configuration.target("latest").load();
            latest.migrate();
            latest.validate();
            assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("21");
            assertThat(db.queryForList("SELECT version,script,checksum,success FROM " + schema
                    + ".flyway_schema_history WHERE version::integer <= 20 ORDER BY installed_rank"))
                    .isEqualTo(historyBefore);
            assertThat(db.queryForList("SELECT * FROM " + schema + ".lms_user_access")).isEqualTo(users);
            assertThat(db.queryForList("SELECT * FROM " + schema + ".class")).isEqualTo(classes);
            assertThat(db.queryForList("SELECT * FROM " + schema + ".lesson")).isEqualTo(lessons);
            assertThat(db.queryForObject("SELECT checksum FROM " + schema + ".flyway_schema_history WHERE version='21'", Integer.class))
                    .isEqualTo(-1362377202);
            assertThat(db.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname=? AND indexname LIKE 'idx_discussion_%'",
                    Integer.class, schema)).isEqualTo(4);

            UUID root = UUID.randomUUID();
            UUID reply = UUID.randomUUID();
            db.update("INSERT INTO " + schema + ".discussion(discussion_id,class_id,lesson_id,author_id,title,content) VALUES (?,?,?,?, 'Root','Body')",
                    root, classId, lesson, author);
            db.update("INSERT INTO " + schema + ".discussion(discussion_id,class_id,lesson_id,author_id,parent_id,content) VALUES (?,?,?,?,?,'Reply')",
                    reply, classId, lesson, author, root);
            assertThatThrownBy(() -> db.update("DELETE FROM " + schema + ".discussion WHERE discussion_id=?", root))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("DELETE FROM " + schema + ".lesson WHERE lesson_id=?", lesson))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("UPDATE " + schema + ".discussion SET content='  ' WHERE discussion_id=?", reply))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> db.update("UPDATE " + schema + ".discussion SET title=NULL WHERE discussion_id=?", root))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(db.queryForObject("SELECT created_at IS NOT NULL AND updated_at IS NOT NULL FROM " + schema
                    + ".discussion WHERE discussion_id=?", Boolean.class, root)).isTrue();
        } finally {
            // This test's private schema only; never a shared database or Flyway history repair.
            db.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
