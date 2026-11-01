package vn.edu.hcmut.co3103.lms.service.admin;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.dto.admin.AdminUserResponse;
import vn.edu.hcmut.co3103.lms.dto.admin.UpdateUserAccessRequest;
import vn.edu.hcmut.co3103.lms.exception.LastAdminProtectionException;
import vn.edu.hcmut.co3103.lms.repository.LmsSetupStateRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import static org.assertj.core.api.Assertions.within;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.schemas=admin_user_service_test",
        "spring.jpa.properties.hibernate.default_schema=admin_user_service_test"
}, showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(AdminUserService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminUserServiceIntegrationTest {

    @Autowired private AdminUserService service;
    @Autowired private LmsUserAccessRepository users;
    @Autowired private LmsSetupStateRepository states;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    @BeforeEach
    @AfterEach
    void resetIsolatedSchema() {
        jdbc.execute("DELETE FROM admin_user_service_test.lms_setup_state");
        jdbc.execute("DELETE FROM admin_user_service_test.audit_logs");
        users.deleteAllInBatch();
        jdbc.execute("INSERT INTO admin_user_service_test.lms_setup_state (id, is_completed) VALUES ('GLOBAL', FALSE)");
    }

    @Test
    void filtersAndPaginatesPersistedUsers() {
        saveUser("Alice A", LmsRole.TUTOR, AccessStatus.ACTIVE);
        var second = saveUser("Alice B", LmsRole.TUTOR, AccessStatus.ACTIVE);
        saveUser("Alice Student", LmsRole.STUDENT, AccessStatus.ACTIVE);
        saveUser("Alice Suspended", LmsRole.TUTOR, AccessStatus.SUSPENDED);
        saveUser("Bob", LmsRole.TUTOR, AccessStatus.ACTIVE);

        var page = service.getUsers(" ALICE ", LmsRole.TUTOR, AccessStatus.ACTIVE,
                PageRequest.of(1, 1, Sort.by("fullName")));

        assertThat(page.content()).containsExactly(new AdminUserResponse(second.getUserId(), LmsRole.TUTOR,
                AccessStatus.ACTIVE, second.getCreatedAt(), second.getUpdatedAt(), second.getFullName(),
                second.getEmail(), second.getLastLoginAt()));
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.last()).isTrue();

        var all = service.getUsers(null, null, null, PageRequest.of(0, 10, Sort.by("fullName")));
        assertThat(all.content()).hasSize(5);
        assertThat(all.totalElements()).isEqualTo(5);
        var byId = service.getUsers(second.getUserId().toString(), null, null, PageRequest.of(0, 10));
        assertThat(byId.content()).containsExactlyElementsOf(page.content());
        assertThat(service.getUsers("missing", null, null, PageRequest.of(0, 10)).content()).isEmpty();
    }

    @Test
    void persistsPartialUpdatesAndReturnsTheUpdatedTimestamp() {
        var target = saveUser("Student", LmsRole.STUDENT, AccessStatus.ACTIVE);
        var admin = saveUser("Admin", LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);
        new TransactionTemplate(transactions).executeWithoutResult(transaction ->
                states.lockGlobalState().orElseThrow().complete(admin.getUserId()));
        var completedSetup = states.findById("GLOBAL").orElseThrow();

        var result = service.updateUserAccess(target.getUserId(), new UpdateUserAccessRequest(LmsRole.TUTOR, null),
                admin.getUserId());

        var stored = users.findById(target.getUserId()).orElseThrow();
        assertThat(result.role()).isEqualTo(LmsRole.TUTOR);
        assertThat(result.accessStatus()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(result.createdAt()).isEqualTo(target.getCreatedAt());
        assertThat(result.updatedAt()).isAfter(target.getUpdatedAt());
        assertThat(result.updatedAt()).isCloseTo(stored.getUpdatedAt(), within(1, ChronoUnit.MICROS));
        assertThat(stored.getLmsRole()).isEqualTo(LmsRole.TUTOR);
        assertThat(stored.getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(stored.getFullName()).isEqualTo(target.getFullName());
        assertThat(stored.getEmail()).isEqualTo(target.getEmail());
        assertThat(stored.getLastLoginAt()).isEqualTo(target.getLastLoginAt());
        assertThat(states.findById("GLOBAL").orElseThrow()).usingRecursiveComparison().isEqualTo(completedSetup);
        assertThat(auditCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"STUDENT,", ", SUSPENDED", "TUTOR, SUSPENDED"})
    void suspendedAdministratorsDoNotAllowRemovalOfTheLastActiveAdmin(LmsRole role, AccessStatus status) {
        var target = saveUser("Active Admin", LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);
        saveUser("Suspended Admin", LmsRole.ADMINISTRATOR, AccessStatus.SUSPENDED);

        assertThatThrownBy(() -> service.updateUserAccess(target.getUserId(),
                new UpdateUserAccessRequest(role, status), target.getUserId()))
                .isInstanceOf(LastAdminProtectionException.class)
                .hasMessage("Cannot demote or suspend the last active administrator");

        assertThat(users.findById(target.getUserId()).orElseThrow()).usingRecursiveComparison().isEqualTo(target);
        assertThat(users.countByLmsRoleAndAccessStatus(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE)).isEqualTo(1);
        assertThat(auditCount()).isZero();
    }

    @Test
    void rollsBackFlushedChangesWhenTheTransactionFails() {
        var target = saveUser("Student", LmsRole.STUDENT, AccessStatus.ACTIVE);
        var admin = saveUser("Admin", LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            service.updateUserAccess(target.getUserId(),
                    new UpdateUserAccessRequest(LmsRole.TUTOR, AccessStatus.SUSPENDED), admin.getUserId());
            throw new IllegalStateException("failure after flush");
        })).isInstanceOf(IllegalStateException.class).hasMessage("failure after flush");

        assertThat(users.findById(target.getUserId()).orElseThrow()).usingRecursiveComparison().isEqualTo(target);
        assertThat(auditCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"STUDENT,", ", SUSPENDED", "TUTOR, SUSPENDED"})
    void concurrentUpdatesCannotRemoveBothActiveAdministrators(LmsRole role, AccessStatus status) throws Exception {
        var first = saveUser("First Admin", LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);
        var second = saveUser("Second Admin", LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);
        var request = new UpdateUserAccessRequest(role, status);
        var enteredTransactions = new CountDownLatch(2);
        List<Future<Boolean>> attempts = new ArrayList<>();
        var executor = Executors.newFixedThreadPool(2);
        try {
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                states.lockGlobalState().orElseThrow();
                for (var target : List.of(first, second)) {
                    attempts.add(executor.submit(() -> {
                        try {
                            return new TransactionTemplate(transactions).execute(update -> {
                                enteredTransactions.countDown();
                                service.updateUserAccess(target.getUserId(), request, target.getUserId());
                                return true;
                            });
                        } catch (LastAdminProtectionException protectedAdmin) {
                            return false;
                        }
                    }));
                }
                try {
                    assertThat(enteredTransactions.await(10, TimeUnit.SECONDS)).isTrue();
                    for (var attempt : attempts) {
                        assertThatThrownBy(() -> attempt.get(100, TimeUnit.MILLISECONDS))
                                .isInstanceOf(TimeoutException.class);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });

            assertThat(List.of(attempts.get(0).get(15, TimeUnit.SECONDS), attempts.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(users.countByLmsRoleAndAccessStatus(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE)).isEqualTo(1);
            assertThat(auditCount()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private LmsUserAccess saveUser(String name, LmsRole role, AccessStatus status) {
        UUID id = UUID.randomUUID();
        Instant originalTime = Instant.parse("2025-01-01T00:00:00Z");
        users.saveAndFlush(new LmsUserAccess(id, name, id + "@example.com", role, status,
                originalTime, originalTime, originalTime));
        return users.findById(id).orElseThrow();
    }

    private long auditCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_user_service_test.audit_logs", Long.class);
    }
}
