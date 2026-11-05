package vn.edu.hcmut.co3103.lms.bootstrap;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;

import java.util.UUID;
import java.util.regex.Pattern;

public class AdminBootstrapRunner implements CommandLineRunner {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final AdminBootstrapService service;
    private final Environment environment;

    public AdminBootstrapRunner(AdminBootstrapService service, Environment environment) {
        this.service = service;
        this.environment = environment;
    }

    @Override
    public void run(String... args) {
        String userId = environment.getProperty("lms.bootstrap.admin.user-id");
        if (userId == null || !UUID_PATTERN.matcher(userId).matches()) {
            throw new IllegalArgumentException("lms.bootstrap.admin.user-id must be a canonical UUID.");
        }
        AdminBootstrapService.Outcome outcome = service.bootstrap(UUID.fromString(userId),
                environment.getProperty("lms.bootstrap.admin.full-name"),
                environment.getProperty("lms.bootstrap.admin.email"));
        if (outcome == AdminBootstrapService.Outcome.EXISTING_ADMINISTRATOR) {
            throw new IllegalStateException("An administrator already exists; bootstrap is permanently closed.");
        }
    }
}
