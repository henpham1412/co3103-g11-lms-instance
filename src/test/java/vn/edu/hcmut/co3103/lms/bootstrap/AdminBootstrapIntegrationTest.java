package vn.edu.hcmut.co3103.lms.bootstrap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsSetupState;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.repository.LmsSetupStateRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.schemas=bootstrap_service_test",
        "spring.jpa.properties.hibernate.default_schema=bootstrap_service_test"
}, showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({FlywayAutoConfiguration.class, ValidationAutoConfiguration.class})
@Import(AdminBootstrapService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminBootstrapIntegrationTest {

    @Autowired private AdminBootstrapService service;
    @Autowired private LmsUserAccessRepository users;
    @Autowired private LmsSetupStateRepository states;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    @AfterEach
    void resetIsolatedSchema() {
        jdbc.execute("DELETE FROM bootstrap_service_test.lms_setup_state");
        users.deleteAllInBatch();
        jdbc.execute("INSERT INTO bootstrap_service_test.lms_setup_state (id, is_completed) VALUES ('GLOBAL', FALSE)");
    }

    @Test
    void createsAdministratorAndCommitsCompletion() {
        UUID id = UUID.randomUUID();
        Instant before = Instant.now().minusSeconds(1);
        assertThat(service.bootstrap(id, "Initial Admin", "admin@example.com"))
                .isEqualTo(AdminBootstrapService.Outcome.CREATED);

        LmsUserAccess user = users.findById(id).orElseThrow();
        assertThat(user.getLmsRole()).isEqualTo(LmsRole.ADMINISTRATOR);
        assertThat(user.getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(user.getFullName()).isEqualTo("Initial Admin");
        assertThat(user.getEmail()).isEqualTo("admin@example.com");
        LmsSetupState state = state();
        assertThat(state.isCompleted()).isTrue();
        assertThat(state.getCompletedAt()).isBetween(before, Instant.now());
        assertThat(state.getBootstrappedBy()).isEqualTo(id);
    }

    @Test
    void refusesSecondInvocationWithoutChangingStateOrUsers() {
        UUID first = UUID.randomUUID();
        service.bootstrap(first, "First", "first@example.com");
        Instant completedAt = state().getCompletedAt();
        UUID second = UUID.randomUUID();
        assertThatThrownBy(() -> service.bootstrap(second, "Second", "second@example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already complete");
        assertThat(users.findById(second)).isEmpty();
        assertThat(users.count()).isEqualTo(1);
        assertThat(state().getCompletedAt()).isEqualTo(completedAt);
        assertThat(state().getBootstrappedBy()).isEqualTo(first);
    }

    @Test
    void existingActiveAdminCommitsLockoutEvenThoughRunnerFails() {
        LmsUserAccess existing = saveUser(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);
        UUID candidate = UUID.randomUUID();
        var runner = new AdminBootstrapRunner(service, new MockEnvironment()
                .withProperty("lms.bootstrap.admin.user-id", candidate.toString()));
        assertThatThrownBy(runner::run).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active administrator");
        assertThat(state().isCompleted()).isTrue();
        assertThat(state().getCompletedAt()).isNotNull();
        assertThat(state().getBootstrappedBy()).isNull();
        assertThat(users.findById(candidate)).isEmpty();
        users.deleteById(existing.getUserId());
        assertThatThrownBy(() -> service.bootstrap(candidate, "Admin", "admin@example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already complete");
    }

    @ParameterizedTest
    @ValueSource(strings = {"suspend", "demote", "delete"})
    void removingAdministratorAccessNeverReopensBootstrap(String change) {
        UUID id = UUID.randomUUID();
        service.bootstrap(id, "Admin", "admin@example.com");
        Instant completedAt = state().getCompletedAt();
        LmsUserAccess admin = users.findById(id).orElseThrow();
        switch (change) {
            case "suspend" -> { admin.setAccessStatus(AccessStatus.SUSPENDED); users.saveAndFlush(admin); }
            case "demote" -> { admin.setLmsRole(LmsRole.STUDENT); users.saveAndFlush(admin); }
            case "delete" -> users.deleteById(id);
            default -> throw new AssertionError(change);
        }
        assertThatThrownBy(() -> service.bootstrap(UUID.randomUUID(), "Other", "other@example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already complete");
        assertThat(state().isCompleted()).isTrue();
        assertThat(state().getCompletedAt()).isEqualTo(completedAt);
        assertThat(state().getBootstrappedBy()).isEqualTo(change.equals("delete") ? null : id);
    }

    @Test
    void promotesAndActivatesExistingUserWithoutRequiringProjectionArguments() {
        LmsUserAccess existing = saveUser(LmsRole.TUTOR, AccessStatus.SUSPENDED);
        assertThat(service.bootstrap(existing.getUserId(), null, null))
                .isEqualTo(AdminBootstrapService.Outcome.CREATED);
        LmsUserAccess updated = users.findById(existing.getUserId()).orElseThrow();
        assertThat(updated.getLmsRole()).isEqualTo(LmsRole.ADMINISTRATOR);
        assertThat(updated.getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(updated.getFullName()).isEqualTo(existing.getFullName());
        assertThat(updated.getEmail()).isEqualTo(existing.getEmail());
        assertThat(updated.getCreatedAt()).isEqualTo(existing.getCreatedAt());
    }

    @Test
    void suspendedAdministratorDoesNotBlockFirstBootstrap() {
        LmsUserAccess suspended = saveUser(LmsRole.ADMINISTRATOR, AccessStatus.SUSPENDED);
        assertThat(service.bootstrap(suspended.getUserId(), null, null))
                .isEqualTo(AdminBootstrapService.Outcome.CREATED);
        assertThat(users.findById(suspended.getUserId()).orElseThrow().getAccessStatus())
                .isEqualTo(AccessStatus.ACTIVE);
    }

    @Test
    void sameEmailDoesNotMergeDifferentIdentityUuids() {
        LmsUserAccess student = saveUser(LmsRole.STUDENT, AccessStatus.ACTIVE);
        UUID adminId = UUID.randomUUID();
        service.bootstrap(adminId, "Admin", student.getEmail());
        assertThat(users.count()).isEqualTo(2);
        assertThat(users.findById(student.getUserId()).orElseThrow().getLmsRole()).isEqualTo(LmsRole.STUDENT);
        assertThat(users.findById(adminId).orElseThrow().getLmsRole()).isEqualTo(LmsRole.ADMINISTRATOR);
    }

    @Test
    void missingSingletonFailsClosedAndIsNeverRecreated() {
        states.deleteAllInBatch();
        assertThatThrownBy(() -> service.bootstrap(UUID.randomUUID(), "Admin", "admin@example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing");
        assertThat(states.count()).isZero();
        assertThat(users.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completionFailureRollsBackInsertedOrPromotedUser(boolean existingUser) {
        UUID id = existingUser ? saveUser(LmsRole.STUDENT, AccessStatus.SUSPENDED).getUserId() : UUID.randomUUID();
        jdbc.execute("ALTER TABLE bootstrap_service_test.lms_setup_state ADD CONSTRAINT reject_test_completion CHECK (is_completed = FALSE)");
        try {
            assertThatThrownBy(() -> service.bootstrap(id, "Admin", "admin@example.com"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(state().isCompleted()).isFalse();
            assertThat(state().getCompletedAt()).isNull();
            assertThat(state().getBootstrappedBy()).isNull();
            if (existingUser) {
                LmsUserAccess unchanged = users.findById(id).orElseThrow();
                assertThat(unchanged.getLmsRole()).isEqualTo(LmsRole.STUDENT);
                assertThat(unchanged.getAccessStatus()).isEqualTo(AccessStatus.SUSPENDED);
            } else {
                assertThat(users.findById(id)).isEmpty();
            }
        } finally {
            jdbc.execute("ALTER TABLE bootstrap_service_test.lms_setup_state DROP CONSTRAINT reject_test_completion");
        }
        assertThat(service.bootstrap(id, "Admin", "admin@example.com"))
                .isEqualTo(AdminBootstrapService.Outcome.CREATED);
    }

    @Test
    void failureAfterBothWritesWereFlushedRollsBackBoth() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            service.bootstrap(id, "Admin", "admin@example.com");
            states.flush();
            assertThat(state().isCompleted()).isTrue();
            throw new IllegalStateException("failure after flush");
        })).isInstanceOf(IllegalStateException.class).hasMessage("failure after flush");
        assertThat(users.findById(id)).isEmpty();
        assertThat(state().isCompleted()).isFalse();
        assertThat(state().getCompletedAt()).isNull();
    }

    @Test
    void concurrentTransactionsBlockOnDatabaseLockAndOnlyOneSucceeds() throws Exception {
        int contenders = 6;
        CountDownLatch enteredTransactions = new CountDownLatch(contenders);
        List<Future<UUID>> attempts = new ArrayList<>();
        var executor = Executors.newFixedThreadPool(contenders);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                states.lockGlobalState().orElseThrow();
                for (int i = 0; i < contenders; i++) {
                    attempts.add(executor.submit(() -> {
                        UUID id = UUID.randomUUID();
                        try {
                            return new TransactionTemplate(transactions).execute(transaction -> {
                                enteredTransactions.countDown();
                                service.bootstrap(id, "Concurrent Admin", id + "@example.com");
                                return id;
                            });
                        } catch (IllegalStateException rejected) {
                            assertThat(rejected).hasMessageContaining("already complete");
                            return null;
                        }
                    }));
                }
                try {
                    assertThat(enteredTransactions.await(10, TimeUnit.SECONDS)).isTrue();
                    // Every transaction is open on its own connection, but the database row is locked here.
                    for (Future<UUID> attempt : attempts) {
                        assertThatThrownBy(() -> attempt.get(100, TimeUnit.MILLISECONDS))
                                .isInstanceOf(TimeoutException.class);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            List<UUID> winners = new ArrayList<>();
            for (Future<UUID> attempt : attempts) {
                UUID result = attempt.get(15, TimeUnit.SECONDS);
                if (result != null) winners.add(result);
            }
            assertThat(winners).hasSize(1);
            assertThat(users.count()).isEqualTo(1);
            assertThat(state().isCompleted()).isTrue();
            assertThat(state().getBootstrappedBy()).isEqualTo(winners.getFirst());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private LmsSetupState state() {
        return states.findById(LmsSetupState.GLOBAL_ID).orElseThrow();
    }

    private LmsUserAccess saveUser(LmsRole role, AccessStatus status) {
        UUID id = UUID.randomUUID();
        users.saveAndFlush(new LmsUserAccess(id, "Existing User", id + "@example.com",
                role, status, null, null, null));
        return users.findById(id).orElseThrow();
    }
}
