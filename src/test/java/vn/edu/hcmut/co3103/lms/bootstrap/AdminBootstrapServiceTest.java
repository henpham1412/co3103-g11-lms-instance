package vn.edu.hcmut.co3103.lms.bootstrap;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsSetupState;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.repository.LmsSetupStateRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminBootstrapServiceTest {

    private static ValidatorFactory validators;
    private final UUID userId = UUID.randomUUID();
    private final LmsSetupStateRepository states = mock(LmsSetupStateRepository.class);
    private final LmsUserAccessRepository users = mock(LmsUserAccessRepository.class);
    private final LmsSetupState state = mock(LmsSetupState.class);
    private AdminBootstrapService service;

    @BeforeAll
    static void createValidator() {
        validators = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeValidator() {
        validators.close();
    }

    @BeforeEach
    void setUp() {
        service = new AdminBootstrapService(states, users, validators.getValidator());
        when(states.lockGlobalState()).thenReturn(Optional.of(state));
    }

    @Test
    void locksAndChecksStateBeforeCreatingAdministrator() {
        assertThat(service.bootstrap(userId, "Initial Admin", "admin@example.com"))
                .isEqualTo(AdminBootstrapService.Outcome.CREATED);

        var order = inOrder(states, state, users);
        order.verify(states).lockGlobalState();
        order.verify(state).isCompleted();
        order.verify(users).findFirstByLmsRoleOrderByCreatedAtAscUserIdAsc(LmsRole.ADMINISTRATOR);
        order.verify(users).findById(userId);
        order.verify(users).saveAndFlush(argThat(user -> user.getUserId().equals(userId)
                && user.getLmsRole() == LmsRole.ADMINISTRATOR
                && user.getAccessStatus() == AccessStatus.ACTIVE));
        order.verify(state).complete(userId);
    }

    @Test
    void rejectsCompletedSetupBeforeAnyUserLookup() {
        when(state.isCompleted()).thenReturn(true);
        assertThatThrownBy(() -> service.bootstrap(userId, null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already complete");
        verifyNoInteractions(users);
        verify(state, never()).complete(any());
    }

    @Test
    void rejectsMissingSetupRowWithoutCreatingIt() {
        when(states.lockGlobalState()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.bootstrap(userId, null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing");
        verifyNoInteractions(users);
        verify(states, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(AccessStatus.class)
    void recordsExistingAdminWithoutThrowingAwayTheCompletionTransaction(AccessStatus status) {
        LmsUserAccess existing = new LmsUserAccess(UUID.randomUUID(), "Existing Admin", "existing@example.com",
                LmsRole.ADMINISTRATOR, status, null, null, null);
        when(users.findFirstByLmsRoleOrderByCreatedAtAscUserIdAsc(LmsRole.ADMINISTRATOR))
                .thenReturn(Optional.of(existing));
        assertThat(service.bootstrap(userId, null, null))
                .isEqualTo(AdminBootstrapService.Outcome.EXISTING_ADMINISTRATOR);
        var order = inOrder(states, state, users);
        order.verify(states).lockGlobalState();
        order.verify(state).isCompleted();
        order.verify(users).findFirstByLmsRoleOrderByCreatedAtAscUserIdAsc(LmsRole.ADMINISTRATOR);
        order.verify(state).complete(existing.getUserId());
        assertThat(existing.getLmsRole()).isEqualTo(LmsRole.ADMINISTRATOR);
        assertThat(existing.getAccessStatus()).isEqualTo(status);
        assertThat(existing.getFullName()).isEqualTo("Existing Admin");
        assertThat(existing.getEmail()).isEqualTo("existing@example.com");
        verifyNoMoreInteractions(users);
        verify(users, never()).findById(any());
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void activatesExistingUuidWithoutOverwritingIdentityProjection() {
        LmsUserAccess existing = new LmsUserAccess(userId, "Original Name", "original@example.com",
                LmsRole.STUDENT, AccessStatus.SUSPENDED, null, null, null);
        when(users.findById(userId)).thenReturn(Optional.of(existing));
        service.bootstrap(userId, "Ignored Name", "ignored@example.com");
        assertThat(existing.getFullName()).isEqualTo("Original Name");
        assertThat(existing.getEmail()).isEqualTo("original@example.com");
        assertThat(existing.getLmsRole()).isEqualTo(LmsRole.ADMINISTRATOR);
        assertThat(existing.getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsMissingNameForNewProjection(String name) {
        assertThatThrownBy(() -> service.bootstrap(userId, name, "admin@example.com"))
                .isInstanceOf(ConstraintViolationException.class);
        verify(users, never()).saveAndFlush(any());
        verify(state, never()).complete(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-an-email", "user@", "@example.com"})
    void rejectsInvalidEmailForNewProjection(String email) {
        assertThatThrownBy(() -> service.bootstrap(userId, "Admin", email))
                .isInstanceOf(ConstraintViolationException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void rejectsOversizedProjectionAndMissingUuid() {
        assertThatThrownBy(() -> service.bootstrap(userId, "N".repeat(151), "admin@example.com"))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> service.bootstrap(null, "Admin", "admin@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void propagatesDatabaseFailureWithoutCompletingSetup() {
        doThrow(new IllegalStateException("storage failure")).when(users).saveAndFlush(any());
        assertThatThrownBy(() -> service.bootstrap(userId, "Admin", "admin@example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessage("storage failure");
        verify(state, never()).complete(any());
    }
}
