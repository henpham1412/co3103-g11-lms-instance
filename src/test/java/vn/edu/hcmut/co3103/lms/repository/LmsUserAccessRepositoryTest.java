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
    void createsReadsUpdatesAndDeletesUserAccess() {
        LmsUserAccess user = newUser(LmsRole.STUDENT, AccessStatus.ACTIVE);
        Instant beforeSave = Instant.now().truncatedTo(ChronoUnit.MICROS);
        repository.saveAndFlush(user);
        entityManager.clear();

        LmsUserAccess stored = repository.findById(user.getUserId()).orElseThrow();
        assertThat(stored.getUserId()).isEqualTo(user.getUserId());
        assertThat(stored.getFullName()).isEqualTo(user.getFullName());
        assertThat(stored.getEmail()).isEqualTo(user.getEmail());
        assertThat(stored.getLmsRole()).isEqualTo(LmsRole.STUDENT);
        assertThat(stored.getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(stored.getLastLoginAt()).isNull();
        assertThat(stored.getCreatedAt()).isBetween(beforeSave, Instant.now());
        assertThat(stored.getUpdatedAt()).isEqualTo(stored.getCreatedAt());
        Instant createdAt = stored.getCreatedAt();

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

        repository.delete(updated);
        repository.flush();
        entityManager.clear();
        assertThat(repository.findById(user.getUserId())).isEmpty();
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
        assertThat(updated.getUpdatedAt()).isBetween(beforeUpdate, Instant.now());
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
        assertThat(updated.getUpdatedAt()).isAfter(originalTime)
                .isBetween(beforeUpdate, Instant.now());
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

    private LmsUserAccess newUser(LmsRole role, AccessStatus status) {
        UUID userId = UUID.randomUUID();
        return new LmsUserAccess(userId, "Test User", userId + "@example.com",
                role, status, null, null, null);
    }
}
