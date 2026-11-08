package vn.edu.hcmut.co3103.lms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(modifyOnCreate = true)
public class JpaAuditingConfig {
}