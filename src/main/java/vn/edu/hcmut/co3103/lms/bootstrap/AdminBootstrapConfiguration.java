package vn.edu.hcmut.co3103.lms.bootstrap;

import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import vn.edu.hcmut.co3103.lms.repository.LmsSetupStateRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;

@Configuration(proxyBeanMethods = false)
@ConditionalOnNotWebApplication
@ConditionalOnProperty(prefix = "lms.bootstrap.admin", name = "enabled", havingValue = "true")
public class AdminBootstrapConfiguration {

    @Bean
    AdminBootstrapService adminBootstrapService(LmsSetupStateRepository states,
                                               LmsUserAccessRepository users, Validator validator) {
        return new AdminBootstrapService(states, users, validator);
    }

    @Bean
    AdminBootstrapRunner adminBootstrapRunner(AdminBootstrapService service, Environment environment) {
        return new AdminBootstrapRunner(service, environment);
    }
}
