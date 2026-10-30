package vn.edu.hcmut.co3103.lms.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AdminBootstrapRunnerTest {

    private final AdminBootstrapService service = mock(AdminBootstrapService.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final AdminBootstrapRunner runner = new AdminBootstrapRunner(service, environment);

    @Test
    void passesCanonicalUuidAndProjectionToTransactionalService() {
        UUID id = UUID.randomUUID();
        environment.withProperty("lms.bootstrap.admin.user-id", id.toString().toUpperCase())
                .withProperty("lms.bootstrap.admin.full-name", "Initial Admin")
                .withProperty("lms.bootstrap.admin.email", "admin@example.com");
        when(service.bootstrap(id, "Initial Admin", "admin@example.com"))
                .thenReturn(AdminBootstrapService.Outcome.CREATED);
        runner.run();
        verify(service).bootstrap(id, "Initial Admin", "admin@example.com");
    }

    @Test
    void rejectsExistingAdminAfterTransactionalServiceReturns() {
        UUID id = UUID.randomUUID();
        environment.withProperty("lms.bootstrap.admin.user-id", id.toString());
        when(service.bootstrap(id, null, null))
                .thenReturn(AdminBootstrapService.Outcome.EXISTING_ADMINISTRATOR);
        assertThatThrownBy(runner::run).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active administrator");
        verify(service).bootstrap(id, null, null);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-uuid", "1-1-1-1-1",
            " 123e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-12d3-a456-426614174000 ",
            "123e4567e89b12d3a456426614174000"})
    void rejectsMissingOrNonCanonicalUuidBeforeCallingService(String value) {
        if (value != null) {
            environment.withProperty("lms.bootstrap.admin.user-id", value);
        }
        assertThatThrownBy(runner::run).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical UUID");
        verifyNoInteractions(service);
    }
}
