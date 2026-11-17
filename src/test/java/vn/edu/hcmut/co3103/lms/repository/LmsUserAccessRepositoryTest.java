package vn.edu.hcmut.co3103.lms.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=validate", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class LmsUserAccessRepositoryTest {

    @Autowired
    private LmsUserAccessRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void newerUnchangedProfileStillPreventsAnIntermediateTokenFromRollingItBack() {
        var user = repository.saveAndFlush(newUser(LmsRole.TUTOR, AccessStatus.SUSPENDED));
        Instant issuedAt = Instant.parse("2030-01-01T00:00:00Z");
        assertThat(repository.synchronizeIdentity(user.getUserId(), user.getFullName(), user.getEmail(),
                issuedAt, issuedAt)).isEqualTo(1);
        assertThat(repository.synchronizeIdentity(user.getUserId(), user.getFullName(), user.getEmail(),
                issuedAt.plusSeconds(30), issuedAt.plusSeconds(30))).isEqualTo(1);
        var newer = repository.findById(user.getUserId()).orElseThrow();

        assertThat(repository.synchronizeIdentity(user.getUserId(), "Intermediate Name", "old@example.test",
                issuedAt.plusSeconds(15), issuedAt.plusSeconds(31))).isZero();
        assertThat(repository.findById(user.getUserId()).orElseThrow()).usingRecursiveComparison().isEqualTo(newer);
        assertThat(newer.getLastSyncedProfileIat()).isEqualTo(issuedAt.plusSeconds(30));
        assertThat(newer.getLastLoginTokenIat()).isNull();
        assertThat(newer.getLastLoginAt()).isNull();
    }

    @ParameterizedTest
    @EnumSource(AccessStatus.class)
    void staleIdentityUpdateIsAnAtomicNoOp(AccessStatus status) {
        var user = repository.saveAndFlush(newUser(LmsRole.TUTOR, status));
        entityManager.refresh(user);
        Instant issuedAt = Instant.parse("2030-01-01T00:00:00.123456789Z");
        assertThat(repository.synchronizeIdentity(user.getUserId(), "Newer Name", "newer@example.test",
                issuedAt, issuedAt.truncatedTo(ChronoUnit.MICROS))).isEqualTo(1);
        var newer = repository.findById(user.getUserId()).orElseThrow();

        // Bypass the converter's snapshot check to exercise the database guard under a stale read.
        assertThat(repository.synchronizeIdentity(user.getUserId(), "Older Name", "older@example.test",
                issuedAt.minusNanos(1), issuedAt.plusSeconds(1))).isZero();
        var stored = repository.findById(user.getUserId()).orElseThrow();
        assertThat(stored).usingRecursiveComparison().isEqualTo(newer);
        assertThat(stored.getUserId()).isEqualTo(user.getUserId());
        assertThat(stored.getLmsRole()).isEqualTo(user.getLmsRole());
        assertThat(stored.getAccessStatus()).isEqualTo(status);
        assertThat(stored.getCreatedAt()).isEqualTo(user.getCreatedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2030-01-01T00:00:45Z", "2030-01-01T00:00:45.000000001Z",
            "2030-01-01T00:00:45.999999999Z"})
    void futureIssuedTokenReuseDoesNotUpdateLoginOrUpdatedAt(String timestamp) {
        var user = repository.saveAndFlush(newUser(LmsRole.STUDENT, AccessStatus.ACTIVE));
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        Instant issuedAt = Instant.parse(timestamp);
        assertThat(repository.synchronizeIdentity(user.getUserId(), "Future Name", "future@example.test",
                issuedAt, now)).isEqualTo(1);
        var first = repository.findById(user.getUserId()).orElseThrow();
        assertThat(first.getLastLoginAt()).isEqualTo(now);
        assertThat(first.getLastLoginTokenIat()).isEqualTo(issuedAt);
        assertThat(first.getLastSyncedProfileIat()).isEqualTo(issuedAt);

        for (int seconds : List.of(1, 20, 46)) {
            assertThat(repository.synchronizeIdentity(user.getUserId(), "Future Name", "future@example.test",
                    issuedAt, now.plusSeconds(seconds))).isZero();
            assertThat(repository.findById(user.getUserId()).orElseThrow()).usingRecursiveComparison().isEqualTo(first);
        }
        assertThat(repository.synchronizeIdentity(user.getUserId(), null, null, issuedAt.plusNanos(1),
                now.plusSeconds(47))).isEqualTo(1);
        var next = repository.findById(user.getUserId()).orElseThrow();
        assertThat(next.getLastLoginTokenIat()).isEqualTo(issuedAt.plusNanos(1));
        assertThat(next.getLastLoginAt()).isEqualTo(now.plusSeconds(47));
        assertThat(next.getLastSyncedProfileIat()).isEqualTo(issuedAt);
        assertThat(next.getFullName()).isEqualTo(first.getFullName());
        assertThat(next.getEmail()).isEqualTo(first.getEmail());
    }

    @Test
    void equalIssuanceCanCompletePartialProfileWithoutRecordingAnotherLogin() {
        var user = repository.saveAndFlush(newUser(LmsRole.STUDENT, AccessStatus.ACTIVE));
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        assertThat(repository.synchronizeIdentity(user.getUserId(), null, "partial@example.test", now, now)).isEqualTo(1);
        assertThat(repository.synchronizeIdentity(user.getUserId(), "Partial Name", null, now, now.plusSeconds(1))).isEqualTo(1);
        var partial = repository.findById(user.getUserId()).orElseThrow();
        assertThat(partial.getFullName()).isEqualTo("Partial Name");
        assertThat(partial.getEmail()).isEqualTo("partial@example.test");
        assertThat(partial.getLastLoginAt()).isEqualTo(now);
        assertThat(partial.getLastLoginTokenIat()).isEqualTo(now);
        assertThat(partial.getLastSyncedProfileIat()).isEqualTo(now);
        assertThat(partial.getUpdatedAt()).isEqualTo(now.plusSeconds(1));
        assertThat(repository.synchronizeIdentity(user.getUserId(), "Partial Name", null, now, now.plusSeconds(2))).isZero();
        assertThat(repository.synchronizeIdentity(user.getUserId(), "Undated Name", null, null, now.plusSeconds(3))).isZero();
        assertThat(repository.findById(user.getUserId()).orElseThrow()).usingRecursiveComparison().isEqualTo(partial);
    }

    @Test
    void staleProfileCannotOverwriteDisplayWhenAnIndependentLoginIsRecorded() {
        var user = repository.saveAndFlush(newUser(LmsRole.TUTOR, AccessStatus.SUSPENDED));
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        repository.synchronizeIdentity(user.getUserId(), "Latest Name", "latest@example.test", now, now);
        jdbcTemplate.update("UPDATE lms_user_access SET access_status = 'ACTIVE' WHERE user_id = ?", user.getUserId());

        assertThat(repository.synchronizeIdentity(user.getUserId(), "Old Name", "old@example.test",
                now.minusSeconds(1), now.plusSeconds(1))).isEqualTo(1);
        var stored = repository.findById(user.getUserId()).orElseThrow();
        assertThat(stored.getFullName()).isEqualTo("Latest Name");
        assertThat(stored.getEmail()).isEqualTo("latest@example.test");
        assertThat(stored.getLastSyncedProfileIat()).isEqualTo(now);
        assertThat(stored.getLastLoginTokenIat()).isEqualTo(now.minusSeconds(1));
        assertThat(stored.getLastLoginAt()).isEqualTo(now.plusSeconds(1));
        assertThat(stored.getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(stored.getLmsRole()).isEqualTo(LmsRole.TUTOR);
    }

    @Test
    void createsReadsUpdatesAndDeletesUserAccess() {
        Instant originalTime = Instant.parse("2020-01-01T00:00:00Z");
        LmsUserAccess user = new LmsUserAccess(UUID.randomUUID(), "Test User",
                UUID.randomUUID() + "@example.com", LmsRole.STUDENT,
                AccessStatus.ACTIVE, null, originalTime, originalTime);
        repository.saveAndFlush(user);
        entityManager.clear();

        LmsUserAccess stored = repository.findById(user.getUserId()).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo(user.getUserId());
        assertThat(stored.getFullName()).isEqualTo(user.getFullName());
        assertThat(stored.getEmail()).isEqualTo(user.getEmail());
        assertThat(stored.getLmsRole()).isEqualTo(LmsRole.STUDENT);
        assertThat(stored.getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(stored.getLastLoginAt()).isNull();
        assertThat(stored.getCreatedAt()).isEqualTo(originalTime);
        assertThat(stored.getUpdatedAt()).isEqualTo(stored.getCreatedAt());
        Instant createdAt = stored.getCreatedAt();

        Instant beforeUpdate = Instant.now();
        stored.setFullName("Updated Name");
        stored.setEmail("updated-" + user.getEmail());
        stored.setLmsRole(LmsRole.TUTOR);
        repository.flush();
        entityManager.clear();

        LmsUserAccess updated = repository.findById(user.getUserId()).orElseThrow();
        assertThat(updated.getFullName()).isEqualTo("Updated Name");
        assertThat(updated.getEmail()).isEqualTo("updated-" + user.getEmail());
        assertThat(updated.getLmsRole()).isEqualTo(LmsRole.TUTOR);
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfter(createdAt);
        assertTimestampBetween(updated.getUpdatedAt(), beforeUpdate, Instant.now());

        repository.delete(updated);
        repository.flush();
        entityManager.clear();
        assertThat(repository.findById(user.getUserId())).isEmpty();
    }

    @Test
    void generatesCreationAndUpdateTimestampsWhenAbsent() {
        LmsUserAccess user = newUser(LmsRole.STUDENT, AccessStatus.ACTIVE);
        Instant beforeSave = Instant.now();
        repository.saveAndFlush(user);
        entityManager.clear();

        LmsUserAccess stored = repository.findById(user.getUserId()).orElseThrow();
        assertTimestampBetween(stored.getCreatedAt(), beforeSave, Instant.now());
        assertThat(stored.getUpdatedAt()).isEqualTo(stored.getCreatedAt());
    }

    @ParameterizedTest
    @EnumSource(LmsRole.class)
    void storesRolesAsStringsAndReadsThemBack(LmsRole role) {
        LmsUserAccess user = newUser(role, AccessStatus.ACTIVE);
        repository.saveAndFlush(user);
        entityManager.clear();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT lms_role FROM lms_user_access WHERE user_id = ?",
                String.class, user.getUserId())).isEqualTo(role.name());
        assertThat(repository.findById(user.getUserId()).orElseThrow().getLmsRole())
                .isEqualTo(role);
    }

    @ParameterizedTest
    @EnumSource(AccessStatus.class)
    void updatesAndStoresAccessStatusAsString(AccessStatus status) {
        AccessStatus initialStatus = status == AccessStatus.ACTIVE
                ? AccessStatus.SUSPENDED : AccessStatus.ACTIVE;
        Instant originalTime = Instant.parse("2020-01-01T00:00:00Z");
        LmsUserAccess user = new LmsUserAccess(UUID.randomUUID(), "Test User",
                UUID.randomUUID() + "@example.com", LmsRole.STUDENT,
                initialStatus, null, originalTime, originalTime);
        repository.saveAndFlush(user);
        entityManager.clear();

        LmsUserAccess stored = repository.findById(user.getUserId()).orElseThrow();
        Instant beforeUpdate = Instant.now().truncatedTo(ChronoUnit.MICROS);
        stored.setAccessStatus(status);
        repository.flush();
        entityManager.clear();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT access_status FROM lms_user_access WHERE user_id = ?",
                String.class, user.getUserId())).isEqualTo(status.name());
        LmsUserAccess updated = repository.findById(user.getUserId()).orElseThrow();
        assertThat(updated.getAccessStatus()).isEqualTo(status);
        assertThat(updated.getCreatedAt()).isEqualTo(originalTime);
        assertThat(updated.getUpdatedAt()).isAfter(originalTime);
        assertTimestampBetween(updated.getUpdatedAt(), beforeUpdate, Instant.now());
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "name", "email", "lastLoginAt"})
    void refreshesTimestampWhenUpdatingUserFields(String field) {
        Instant originalTime = Instant.parse("2020-01-01T00:00:00Z");
        LmsUserAccess user = new LmsUserAccess(UUID.randomUUID(), "Test User",
                UUID.randomUUID() + "@example.com", LmsRole.STUDENT,
                AccessStatus.ACTIVE, null, originalTime, originalTime);
        repository.saveAndFlush(user);
        entityManager.clear();

        LmsUserAccess stored = repository.findById(user.getUserId()).orElseThrow();
        Instant beforeUpdate = Instant.now().truncatedTo(ChronoUnit.MICROS);
        switch (field) {
            case "role" -> stored.setLmsRole(LmsRole.TUTOR);
            case "name" -> stored.setFullName("Updated Name");
            case "email" -> stored.setEmail("updated-" + user.getEmail());
            case "lastLoginAt" -> stored.setLastLoginAt(beforeUpdate);
            default -> throw new AssertionError("Unexpected field: " + field);
        }
        repository.flush();
        entityManager.clear();

        LmsUserAccess updated = repository.findById(user.getUserId()).orElseThrow();
        assertThat(updated.getLmsRole()).isEqualTo(stored.getLmsRole());
        assertThat(updated.getFullName()).isEqualTo(stored.getFullName());
        assertThat(updated.getEmail()).isEqualTo(stored.getEmail());
        assertThat(updated.getLastLoginAt()).isEqualTo(stored.getLastLoginAt());
        assertThat(updated.getCreatedAt()).isEqualTo(originalTime);
        assertThat(updated.getUpdatedAt()).isAfter(originalTime);
        assertTimestampBetween(updated.getUpdatedAt(), beforeUpdate, Instant.now());
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-01T07:01:52.525249600Z", "2026-11-01T07:01:52.999999600Z",
            "2026-11-01T07:01:52.525249400Z", "2026-11-01T07:01:52.525249000Z"})
    void timestampBoundsAccountForPostgresPrecisionWithoutHidingInvalidTimes(String timestamp) {
        Instant observed = Instant.parse(timestamp);
        LmsUserAccess user = newUser(LmsRole.STUDENT, AccessStatus.ACTIVE);
        repository.saveAndFlush(user);
        // Cast text in PostgreSQL so the database, rather than the JDBC driver, rounds the nanoseconds.
        jdbcTemplate.update("UPDATE lms_user_access SET updated_at = CAST(? AS TIMESTAMPTZ) WHERE user_id = ?",
                timestamp, user.getUserId());
        entityManager.clear();

        Instant rounded = repository.findById(user.getUserId()).orElseThrow().getUpdatedAt();
        assertThat(rounded).isEqualTo(observed.plusNanos(500).truncatedTo(ChronoUnit.MICROS));
        assertTimestampBetween(rounded, observed, observed);
        assertThatThrownBy(() -> assertTimestampBetween(
                observed.truncatedTo(ChronoUnit.MICROS).minus(1, ChronoUnit.MICROS), observed, observed))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertTimestampBetween(rounded.plus(2, ChronoUnit.MICROS), observed, observed))
                .isInstanceOf(AssertionError.class);
    }

    @ParameterizedTest
    @EnumSource(LmsRole.class)
    void countsAndFindsOnlyTheRequestedRole(LmsRole role) {
        long initialCount = repository.countByLmsRole(role);
        for (LmsRole otherRole : LmsRole.values()) {
            repository.save(newUser(otherRole, AccessStatus.ACTIVE));
        }
        repository.saveAndFlush(newUser(role, AccessStatus.SUSPENDED));
        entityManager.clear();

        assertThat(repository.countByLmsRole(role)).isEqualTo(initialCount + 2);
        assertThat(repository.existsByLmsRole(role)).isTrue();
    }

    @Test
    void returnsZeroAndFalseWhenNoUserHasTheRole() {
        // DataJpaTest rolls back this deletion along with all test data.
        repository.deleteAllInBatch();
        repository.saveAndFlush(newUser(LmsRole.STUDENT, AccessStatus.ACTIVE));
        entityManager.clear();

        assertThat(repository.countByLmsRole(LmsRole.ADMINISTRATOR)).isZero();
        assertThat(repository.existsByLmsRole(LmsRole.ADMINISTRATOR)).isFalse();
        repository.saveAndFlush(newUser(LmsRole.TUTOR, AccessStatus.SUSPENDED));
        assertThat(repository.findFirstByLmsRoleOrderByCreatedAtAscUserIdAsc(LmsRole.ADMINISTRATOR)).isEmpty();
    }

    @Test
    void countsOnlyActiveAdministrators() {
        repository.deleteAllInBatch();
        assertThat(repository.countByLmsRoleAndAccessStatus(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE))
                .isZero();
        repository.saveAllAndFlush(List.of(
                newUser(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE),
                newUser(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE),
                newUser(LmsRole.ADMINISTRATOR, AccessStatus.SUSPENDED),
                newUser(LmsRole.STUDENT, AccessStatus.ACTIVE),
                newUser(LmsRole.TUTOR, AccessStatus.ACTIVE)));
        entityManager.clear();

        assertThat(repository.countByLmsRoleAndAccessStatus(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE))
                .isEqualTo(2);
        assertThat(repository.countByLmsRoleAndAccessStatus(LmsRole.ADMINISTRATOR, AccessStatus.SUSPENDED))
                .isEqualTo(1);
    }

    @Test
    void executesCombinedSpecificationsAndExactUuidSearch() {
        repository.deleteAllInBatch();
        LmsUserAccess byName = newUser(LmsRole.TUTOR, AccessStatus.ACTIVE);
        byName.setFullName("Alice Nguyen");
        byName.setEmail("name@example.com");
        LmsUserAccess byEmail = newUser(LmsRole.TUTOR, AccessStatus.ACTIVE);
        byEmail.setEmail("alice@example.com");
        LmsUserAccess wrongRole = newUser(LmsRole.STUDENT, AccessStatus.ACTIVE);
        wrongRole.setFullName("Alice Student");
        LmsUserAccess suspended = newUser(LmsRole.TUTOR, AccessStatus.SUSPENDED);
        suspended.setFullName("Alice Suspended");
        LmsUserAccess unrelated = newUser(LmsRole.TUTOR, AccessStatus.ACTIVE);
        unrelated.setFullName("100%_\\ Complete");
        repository.saveAllAndFlush(List.of(byName, byEmail, wrongRole, suspended, unrelated));
        entityManager.clear();

        assertThat(repository.findAll(LmsUserAccessSpecification.filter(" ALIce ",
                LmsRole.TUTOR, AccessStatus.ACTIVE))).extracting(LmsUserAccess::getUserId)
                .containsExactlyInAnyOrder(byName.getUserId(), byEmail.getUserId());
        assertThat(repository.findAll(LmsUserAccessSpecification.filter(
                byName.getUserId().toString(), null, null))).extracting(LmsUserAccess::getUserId)
                .containsExactly(byName.getUserId());
        assertThat(repository.findAll(LmsUserAccessSpecification.filter(
                byName.getUserId().toString().substring(0, 8), null, null))).isEmpty();
        assertThat(repository.findAll(LmsUserAccessSpecification.filter("%_\\", null, null)))
                .extracting(LmsUserAccess::getUserId).containsExactly(unrelated.getUserId());
        assertThat(repository.findAll(LmsUserAccessSpecification.filter(" \t", null, null))).hasSize(5);
    }

    @Test
    void findsByEmailAndReturnsEmptyForUnknownEmail() {
        LmsUserAccess user = newUser(LmsRole.TUTOR, AccessStatus.ACTIVE);
        repository.saveAndFlush(user);
        entityManager.clear();

        assertThat(repository.findAllByEmail(user.getEmail()))
                .extracting(LmsUserAccess::getUserId).containsExactly(user.getUserId());
        assertThat(repository.findAllByEmail(UUID.randomUUID() + "@example.com")).isEmpty();
    }

    @Test
    void storesDuplicateEmailProjectionsUnderDistinctIdentityUuids() {
        LmsUserAccess first = newUser(LmsRole.STUDENT, AccessStatus.ACTIVE);
        repository.saveAndFlush(first);
        entityManager.clear();
        LmsUserAccess duplicate = newUser(LmsRole.TUTOR, AccessStatus.ACTIVE);
        duplicate.setEmail(first.getEmail());

        repository.saveAndFlush(duplicate);
        entityManager.clear();

        assertThat(repository.findAllByEmail(first.getEmail()))
                .extracting(LmsUserAccess::getUserId)
                .containsExactlyInAnyOrder(first.getUserId(), duplicate.getUserId());
    }

    @Test
    void persistsMaximumNameAndEmailLengths() {
        LmsUserAccess user = newUser(LmsRole.STUDENT, AccessStatus.ACTIVE);
        user.setFullName("N".repeat(150));
        user.setEmail("a".repeat(64) + "@" + "b".repeat(63) + "."
                + "c".repeat(63) + "." + "d".repeat(62));
        repository.saveAndFlush(user);
        entityManager.clear();

        LmsUserAccess stored = repository.findById(user.getUserId()).orElseThrow();
        assertThat(stored.getFullName()).isEqualTo(user.getFullName()).hasSize(150);
        assertThat(stored.getEmail()).isEqualTo(user.getEmail()).hasSize(255);
    }

    @Test
    void migratesV1UserWith150CharacterNameWithoutDataLoss() {
        // PostgreSQL rolls back this schema and search path with the test transaction.
        jdbcTemplate.execute("CREATE SCHEMA lms_v1_upgrade_test");
        jdbcTemplate.execute("SET LOCAL search_path TO lms_v1_upgrade_test");
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V1__create_lms_user_access.sql"));
            return null;
        });
        UUID userId = UUID.randomUUID();
        String fullName = "N".repeat(150);
        jdbcTemplate.update("""
                INSERT INTO lms_user_access (user_id, full_name, email, lms_role)
                VALUES (?, ?, ?, ?)
                """, userId, fullName, userId + "@example.com", "STUDENT");

        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V2__align_lms_user_access_column_lengths.sql"));
            return null;
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT full_name FROM lms_user_access WHERE user_id = ?",
                String.class, userId)).isEqualTo(fullName).hasSize(150);
    }

    @ParameterizedTest
    @EnumSource(AccessStatus.class)
    void selectsAdministratorByCreationTimeAndUuidRegardlessOfStatus(AccessStatus status) {
        repository.deleteAllInBatch();
        Instant oldest = Instant.parse("2020-01-01T00:00:00Z");
        UUID selected = new UUID(0, 2);
        repository.saveAllAndFlush(List.of(
                new LmsUserAccess(new UUID(0, 1), "New Admin", "same@example.com", LmsRole.ADMINISTRATOR,
                        AccessStatus.ACTIVE, null, oldest.plusSeconds(1), oldest),
                new LmsUserAccess(new UUID(0, 3), "Tied Admin", "same@example.com", LmsRole.ADMINISTRATOR,
                        AccessStatus.ACTIVE, null, oldest, oldest),
                new LmsUserAccess(selected, "Old Admin", "same@example.com", LmsRole.ADMINISTRATOR,
                        status, null, oldest, oldest),
                new LmsUserAccess(new UUID(0, 0), "Student", "same@example.com", LmsRole.STUDENT,
                        AccessStatus.ACTIVE, null, oldest.minusSeconds(1), oldest)));
        entityManager.clear();
        assertThat(repository.findFirstByLmsRoleOrderByCreatedAtAscUserIdAsc(LmsRole.ADMINISTRATOR))
                .get().extracting(LmsUserAccess::getUserId).isEqualTo(selected);
    }

    private static void assertTimestampBetween(Instant actual, Instant before, Instant after) {
        // TIMESTAMPTZ rounds nanoseconds to microseconds, possibly beyond the Java upper bound.
        assertThat(actual).isBetween(before.truncatedTo(ChronoUnit.MICROS),
                after.truncatedTo(ChronoUnit.MICROS).plus(1, ChronoUnit.MICROS));
    }

    private LmsUserAccess newUser(LmsRole role, AccessStatus status) {
        UUID userId = UUID.randomUUID();
        return new LmsUserAccess(userId, "Test User", userId + "@example.com",
                role, status, null, null, null);
    }
}
