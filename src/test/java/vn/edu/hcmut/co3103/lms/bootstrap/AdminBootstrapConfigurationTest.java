package vn.edu.hcmut.co3103.lms.bootstrap;

import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import vn.edu.hcmut.co3103.lms.repository.LmsSetupStateRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AdminBootstrapConfigurationTest {

    @Test
    void bootstrapIsDisabledByDefaultEvenWithoutWeb() {
        new ApplicationContextRunner().withUserConfiguration(AdminBootstrapConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AdminBootstrapRunner.class);
                    assertThat(context).doesNotHaveBean(AdminBootstrapService.class);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "TRUE", "true"})
    void webContextCannotEnableBootstrap(String enabled) {
        new WebApplicationContextRunner().withUserConfiguration(AdminBootstrapConfiguration.class)
                .withPropertyValues("lms.bootstrap.admin.enabled=" + enabled)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(AdminBootstrapRunner.class);
                    assertThat(context).doesNotHaveBean(AdminBootstrapService.class);
                });
    }

    @Test
    void explicitlyDisabledNonWebContextDoesNotCreateRunner() {
        new ApplicationContextRunner().withUserConfiguration(AdminBootstrapConfiguration.class)
                .withPropertyValues("lms.bootstrap.admin.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(AdminBootstrapRunner.class));
    }

    @Test
    void explicitlyEnabledNonWebContextCreatesRunnerAndService() {
        new ApplicationContextRunner().withUserConfiguration(AdminBootstrapConfiguration.class)
                .withPropertyValues("lms.bootstrap.admin.enabled=true")
                .withBean(LmsSetupStateRepository.class, () -> mock(LmsSetupStateRepository.class))
                .withBean(LmsUserAccessRepository.class, () -> mock(LmsUserAccessRepository.class))
                .withBean(Validator.class, () -> mock(Validator.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(AdminBootstrapRunner.class);
                    assertThat(context).hasSingleBean(AdminBootstrapService.class);
                });
    }
}
