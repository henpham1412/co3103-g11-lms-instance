package vn.edu.hcmut.co3103.lms.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.context.WebApplicationContext;
import vn.edu.hcmut.co3103.lms.Co3103G11LmsInstanceApplication;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsSetupState;
import vn.edu.hcmut.co3103.lms.repository.LmsSetupStateRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminBootstrapCliIntegrationTest {

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
        assertThatThrownBy(() -> {
            try (var ignored = application(schema).run("--lms.bootstrap.admin.enabled=true",
                    "--lms.bootstrap.admin.user-id=" + UUID.randomUUID())) {
                throw new AssertionError("Repeated bootstrap must fail startup");
            }
        }).isInstanceOf(IllegalStateException.class).hasStackTraceContaining("already complete");

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

    private SpringApplicationBuilder application(String schema) {
        return new SpringApplicationBuilder(Co3103G11LmsInstanceApplication.class)
                .web(WebApplicationType.NONE)
                .properties("spring.flyway.schemas=" + schema,
                        "spring.jpa.properties.hibernate.default_schema=" + schema,
                        "spring.jpa.hibernate.ddl-auto=validate", "spring.main.banner-mode=off");
    }
}
