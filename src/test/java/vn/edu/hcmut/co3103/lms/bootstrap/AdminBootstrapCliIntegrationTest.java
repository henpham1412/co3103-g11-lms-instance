package vn.edu.hcmut.co3103.lms.bootstrap;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.WebApplicationContext;
import vn.edu.hcmut.co3103.lms.Co3103G11LmsInstanceApplication;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsSetupState;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.repository.LmsSetupStateRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminBootstrapCliIntegrationTest {

    @Test
    void upgradesV1WithDuplicateEmailProjectionsThroughV7() {
        String schema = "bootstrap_v1_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        String email = "shared@example.com";
        try (var context = application(schema).properties("spring.flyway.target=1",
                "spring.jpa.hibernate.ddl-auto=none").run()) {
            assertThat(context.getBean(Flyway.class).info().current().getVersion().getVersion()).isEqualTo("1");
            List.of(
                    new LmsUserAccess(firstId, "First User", email, LmsRole.STUDENT,
                            AccessStatus.ACTIVE, null, null, null),
                    new LmsUserAccess(secondId, "Second User", email, LmsRole.TUTOR,
                            AccessStatus.SUSPENDED, null, null, null))
                    .forEach(user -> insertLegacyUser(context, schema, user));
        }

        try (var context = application(schema).run()) {
            var flyway = context.getBean(Flyway.class);
            flyway.validate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("7");
            assertThat(flyway.info().applied()).filteredOn(info -> info.getVersion() != null)
                    .extracting(info -> info.getVersion().getVersion()).containsExactly("1", "2", "3", "4", "5", "6", "7");
            var users = context.getBean(LmsUserAccessRepository.class);
            assertThat(users.findAll()).extracting(LmsUserAccess::getUserId)
                    .containsExactlyInAnyOrder(firstId, secondId);
            assertThat(users.findAll()).extracting(LmsUserAccess::getEmail).containsExactly(email, email);
            var states = context.getBean(LmsSetupStateRepository.class);
            assertThat(states.findAll()).singleElement().satisfies(state -> {
                assertThat(state.getId()).isEqualTo(LmsSetupState.GLOBAL_ID);
                assertThat(state.isCompleted()).isFalse();
            });
            users.saveAndFlush(new LmsUserAccess(UUID.randomUUID(), "Third User", email, LmsRole.STUDENT,
                    AccessStatus.ACTIVE, null, null, null));
            assertThat(users.count()).isEqualTo(3);
            states.deleteAllInBatch();
            users.deleteAllInBatch();
        }
    }

    @Test
    void actualNonWebStartupBootstrapsAndRemainsClosedAcrossContextRestarts() {
        String schema = "bootstrap_cli_" + UUID.randomUUID().toString().replace("-", "");
        UUID id = UUID.randomUUID();
        Instant completedAt;
        try (ConfigurableApplicationContext context = application(schema).run(
                "--lms.bootstrap.admin.enabled=true", "--lms.bootstrap.admin.user-id=" + id,
                "--lms.bootstrap.admin.full-name=CLI Admin", "--lms.bootstrap.admin.email=cli@example.com")) {
            assertThat(context).isNotInstanceOf(WebApplicationContext.class);
            assertThat(context.getBeansOfType(AdminBootstrapRunner.class)).hasSize(1);
            var user = context.getBean(LmsUserAccessRepository.class).findById(id).orElseThrow();
            assertThat(user.getLmsRole()).isEqualTo(LmsRole.ADMINISTRATOR);
            assertThat(user.getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
            var state = context.getBean(LmsSetupStateRepository.class).findById(LmsSetupState.GLOBAL_ID).orElseThrow();
            assertThat(state.isCompleted()).isTrue();
            completedAt = state.getCompletedAt();
        }

        // The runner fails application startup only after the transactional service has rejected it.
        for (UUID candidate : List.of(id, UUID.randomUUID())) {
            assertThatThrownBy(() -> {
                try (var ignored = application(schema).run("--lms.bootstrap.admin.enabled=true",
                        "--lms.bootstrap.admin.user-id=" + candidate)) {
                    throw new AssertionError("Repeated bootstrap must fail startup");
                }
            }).isInstanceOf(IllegalStateException.class).hasStackTraceContaining("already complete");
        }

        try (ConfigurableApplicationContext context = application(schema).run()) {
            assertThat(context.getBeansOfType(AdminBootstrapRunner.class)).isEmpty();
            var states = context.getBean(LmsSetupStateRepository.class);
            var users = context.getBean(LmsUserAccessRepository.class);
            var state = states.findById(LmsSetupState.GLOBAL_ID).orElseThrow();
            assertThat(state.isCompleted()).isTrue();
            assertThat(state.getCompletedAt()).isEqualTo(completedAt);
            assertThat(state.getBootstrappedBy()).isEqualTo(id);
            assertThat(users.count()).isEqualTo(1);
            states.deleteAllInBatch();
            users.deleteAllInBatch();
        }
    }

    @ParameterizedTest
    @CsvSource({"ACTIVE, suspend", "ACTIVE, demote", "ACTIVE, delete",
            "SUSPENDED, suspend", "SUSPENDED, demote", "SUSPENDED, delete"})
    void upgradeSealsExistingAdministratorBeforeAnyCliInvocation(AccessStatus status, String change) {
        String schema = "bootstrap_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        UUID existingId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();

        // Reproduce an existing installation using the real PostgreSQL V1/V2 migrations.
        try (var context = application(schema).properties("spring.flyway.target=2",
                "spring.jpa.hibernate.ddl-auto=none").run()) {
            assertThat(context.getBean(Flyway.class).info().current().getVersion().getVersion()).isEqualTo("2");
            assertThat(context.getBeansOfType(AdminBootstrapRunner.class)).isEmpty();
            insertLegacyUser(context, schema, new LmsUserAccess(existingId,
                    "Existing Admin", "existing@example.com", LmsRole.ADMINISTRATOR,
                    status, null, null, null));
        }

        Instant beforeMigration = Instant.now().minusSeconds(1);
        Instant completedAt;
        try (var context = application(schema).run()) {
            var flyway = context.getBean(Flyway.class);
            flyway.validate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("7");
            assertThat(flyway.info().applied()).filteredOn(info -> info.getVersion() != null)
                    .extracting(info -> info.getVersion().getVersion()).containsExactly("1", "2", "3", "4", "5", "6", "7");
            assertThat(context.getBeansOfType(AdminBootstrapRunner.class)).isEmpty();
            var state = context.getBean(LmsSetupStateRepository.class).findById(LmsSetupState.GLOBAL_ID).orElseThrow();
            assertThat(state.isCompleted()).isTrue();
            assertThat(state.getCompletedAt()).isBetween(beforeMigration, Instant.now());
            assertThat(state.getBootstrappedBy()).isEqualTo(existingId);
            completedAt = state.getCompletedAt();

            var users = context.getBean(LmsUserAccessRepository.class);
            var existing = users.findById(existingId).orElseThrow();
            switch (change) {
                case "suspend" -> { existing.setAccessStatus(AccessStatus.SUSPENDED); users.saveAndFlush(existing); }
                case "demote" -> { existing.setLmsRole(LmsRole.STUDENT); users.saveAndFlush(existing); }
                case "delete" -> users.deleteById(existingId);
                default -> throw new AssertionError(change);
            }
            assertThat(users.existsByLmsRoleAndAccessStatus(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE)).isFalse();
        }

        // The first actual CLI invocation must refuse even though no active administrator remains.
        assertThatThrownBy(() -> {
            try (var ignored = application(schema).run("--lms.bootstrap.admin.enabled=true",
                    "--lms.bootstrap.admin.user-id=" + candidateId,
                    "--lms.bootstrap.admin.full-name=New Admin",
                    "--lms.bootstrap.admin.email=new@example.com")) {
                throw new AssertionError("Upgrade must permanently close bootstrap");
            }
        }).isInstanceOf(IllegalStateException.class).hasStackTraceContaining("Administrator setup is already complete.");

        try (var context = application(schema).run()) {
            var states = context.getBean(LmsSetupStateRepository.class);
            var users = context.getBean(LmsUserAccessRepository.class);
            var state = states.findById(LmsSetupState.GLOBAL_ID).orElseThrow();
            assertThat(state.isCompleted()).isTrue();
            assertThat(state.getCompletedAt()).isEqualTo(completedAt);
            assertThat(state.getBootstrappedBy()).isEqualTo(change.equals("delete") ? null : existingId);
            assertThat(users.findById(candidateId)).isEmpty();
            assertThat(users.existsByLmsRoleAndAccessStatus(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE)).isFalse();
            assertThat(users.count()).isEqualTo(change.equals("delete") ? 0 : 1);
            states.deleteAllInBatch();
            users.deleteAllInBatch();
        }
    }

    @Test
    void upgradeSelectsOneExistingAdministratorDeterministically() {
        String schema = "bootstrap_multiple_" + UUID.randomUUID().toString().replace("-", "");
        UUID selected = new UUID(0, 2);
        Instant oldest = Instant.parse("2020-01-01T00:00:00Z");
        List<LmsUserAccess> existing;
        try (var context = application(schema).properties("spring.flyway.target=2",
                "spring.jpa.hibernate.ddl-auto=none").run()) {
            existing = List.of(
                    new LmsUserAccess(new UUID(0, 1), "New Admin", "same@example.com", LmsRole.ADMINISTRATOR,
                            AccessStatus.ACTIVE, null, oldest.plusSeconds(1), oldest),
                    new LmsUserAccess(new UUID(0, 3), "Tied Admin", "same@example.com", LmsRole.ADMINISTRATOR,
                            AccessStatus.ACTIVE, null, oldest, oldest),
                    new LmsUserAccess(selected, "Old Admin", "same@example.com", LmsRole.ADMINISTRATOR,
                            AccessStatus.SUSPENDED, null, oldest, oldest));
            existing.forEach(user -> insertLegacyUser(context, schema, user));
        }
        try (var context = application(schema).run()) {
            context.getBean(Flyway.class).validate();
            var states = context.getBean(LmsSetupStateRepository.class);
            var users = context.getBean(LmsUserAccessRepository.class);
            assertThat(states.findAll()).singleElement().satisfies(state -> {
                assertThat(state.isCompleted()).isTrue();
                assertThat(state.getBootstrappedBy()).isEqualTo(selected);
            });
            assertThat(users.findAll()).usingRecursiveFieldByFieldElementComparator()
                    .containsExactlyInAnyOrderElementsOf(existing);
            states.deleteAllInBatch();
            users.deleteAllInBatch();
        }
    }

    private void insertLegacyUser(ConfigurableApplicationContext context, String schema, LmsUserAccess user) {
        // Seed V1/V2 columns directly: the current entity also maps columns introduced by V6.
        context.getBean(JdbcTemplate.class).update("""
                INSERT INTO %s.lms_user_access (user_id, full_name, email, lms_role, access_status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, COALESCE(CAST(? AS TIMESTAMPTZ), CURRENT_TIMESTAMP),
                        COALESCE(CAST(? AS TIMESTAMPTZ), CURRENT_TIMESTAMP))
                """.formatted(schema), user.getUserId(), user.getFullName(), user.getEmail(), user.getLmsRole().name(),
                user.getAccessStatus().name(), user.getCreatedAt() == null ? null : Timestamp.from(user.getCreatedAt()),
                user.getUpdatedAt() == null ? null : Timestamp.from(user.getUpdatedAt()));
    }

    private SpringApplicationBuilder application(String schema) {
        return new SpringApplicationBuilder(Co3103G11LmsInstanceApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.flyway.schemas=" + schema,
                        "spring.jpa.properties.hibernate.default_schema=" + schema,
                        "spring.jpa.hibernate.ddl-auto=validate", "spring.main.banner-mode=off");
    }
}
