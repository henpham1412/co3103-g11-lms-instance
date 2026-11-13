package vn.edu.hcmut.co3103.lms.bootstrap;

import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway re-reads the checksum it stored the first time a migration ran, so editing an already
 * applied {@code V*.sql} breaks every database that has it. These pins turn that edit into a
 * failing build instead of a broken deployment.
 */
class MigrationPolicyTest {

    private static final Map<String, Integer> APPROVED_CHECKSUMS = Map.ofEntries(
            Map.entry("V1__create_lms_user_access.sql", 15260926),
            Map.entry("V2__align_lms_user_access_column_lengths.sql", -231189632),
            Map.entry("V3__create_lms_setup_state.sql", 1480030657),
            Map.entry("V4__create_classes_and_memberships.sql", -1197899662),
            Map.entry("V5__create_learning_content_tables.sql", 1741533483),
            Map.entry("V6__create_assessments.sql", -1763291091),
            Map.entry("V7__create_completion_marks.sql", -1834607830),
            Map.entry("V8__create_last_visit.sql", -1912735881),
            Map.entry("V9__restrict_completion_mark_lesson_delete.sql", 1456857671),
            Map.entry("V10__create_skill_topics_and_prerequisites.sql", -1708516214),
            Map.entry("V11__create_lesson_skill_topics.sql", 261484167),
            Map.entry("V12__create_activity_skill_topics.sql", -1627510611),
            Map.entry("V13__create_question_skill_topics.sql", -415992144),
            Map.entry("V18__create_discussions.sql", -1362377202),
            Map.entry("V19__create_content_reports.sql", 420574687));

    @Test
    void keepsTheMigrationSetDownToTheApprovedFiles() throws IOException {
        assertThat(migrations()).extracting(MigrationFile::fileName)
                .containsExactlyInAnyOrderElementsOf(APPROVED_CHECKSUMS.keySet());
    }

    @Test
    void keepsTheChecksumOfEveryAlreadyAppliedMigration() throws IOException {
        for (MigrationFile migration : migrations()) {
            assertThat(migration.checksum())
                    .as("Flyway checksum of %s - add a new forward migration instead of editing this one",
                            migration.fileName())
                    .isEqualTo(APPROVED_CHECKSUMS.get(migration.fileName()));
        }
    }

    @Test
    void storesMigrationsWithUnixLineEndingsSoEveryCheckoutIsByteIdentical() throws IOException {
        for (MigrationFile migration : migrations()) {
            assertThat(new String(migration.bytes(), StandardCharsets.ISO_8859_1))
                    .as("line endings in %s", migration.fileName())
                    .doesNotContain("\r");
        }
    }

    private static List<MigrationFile> migrations() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/*.sql");
        return Arrays.stream(resources).map(MigrationFile::read).sorted().toList();
    }

    private record MigrationFile(String fileName, byte[] bytes, int checksum)
            implements Comparable<MigrationFile> {

        static MigrationFile read(Resource resource) {
            try (var stream = resource.getInputStream()) {
                byte[] bytes = stream.readAllBytes();
                String fileName = resource.getFilename();
                return new MigrationFile(fileName, bytes, checksum(fileName, bytes));
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot read " + resource, failure);
            }
        }

        private static int checksum(String fileName, byte[] bytes) {
            return ChecksumCalculator.calculate(new LoadableResource() {
                @Override
                public Reader read() {
                    return new StringReader(new String(bytes, StandardCharsets.UTF_8));
                }

                @Override
                public String getAbsolutePath() {
                    return "db/migration/" + fileName;
                }

                @Override
                public String getAbsolutePathOnDisk() {
                    return getAbsolutePath();
                }

                @Override
                public String getFilename() {
                    return fileName;
                }

                @Override
                public String getRelativePath() {
                    return getAbsolutePath();
                }
            });
        }

        @Override
        public int compareTo(MigrationFile other) {
            return fileName.compareTo(other.fileName());
        }
    }
}
