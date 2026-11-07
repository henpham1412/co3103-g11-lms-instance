package vn.edu.hcmut.co3103.lms.bootstrap;

import org.flywaydb.core.api.MigrationVersion;
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

    private static final Map<String, Integer> APPROVED_CHECKSUMS = Map.of(
            "V1__create_lms_user_access.sql", 15260926,
            "V2__align_lms_user_access_column_lengths.sql", -231189632,
            "V3__create_lms_setup_state.sql", 1480030657,
            "V4__create_classes_and_memberships.sql", -1197899662,
            "V5__create_learning_content_tables.sql", 1741533483,
            "V6__index_lms_user_access_default_sort.sql", 354517154,
            "V7__add_identity_sync_watermarks.sql", 1240703539,
            "V8__create_audit_logs.sql", 67085040);

    @Test
    void keepsExactlyTheEightApprovedMigrations() throws IOException {
        List<MigrationFile> migrations = migrations();
        assertThat(migrations).hasSize(8);
        assertThat(migrations).extracting(MigrationFile::fileName)
                .containsExactlyInAnyOrderElementsOf(APPROVED_CHECKSUMS.keySet());
    }

    @Test
    void migrationVersionsAreUniqueRegardlessOfDescription() throws IOException {
        assertThat(migrations()).extracting(migration -> MigrationVersion.fromVersion(
                migration.fileName().substring(1, migration.fileName().indexOf("__"))))
                .doesNotHaveDuplicates();
    }

    @Test
    void keepsTheChecksumOfEveryAlreadyAppliedMigration() throws IOException {
        for (MigrationFile migration : migrations()) {
            assertThat(migration.checksum())
                    .as("Flyway checksum of %s - add a new versioned migration instead of editing this one",
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
