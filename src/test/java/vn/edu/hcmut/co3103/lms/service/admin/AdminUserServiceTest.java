package vn.edu.hcmut.co3103.lms.service.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsSetupState;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.dto.admin.AdminUserResponse;
import vn.edu.hcmut.co3103.lms.dto.admin.UpdateUserAccessRequest;
import vn.edu.hcmut.co3103.lms.exception.LastAdminProtectionException;
import vn.edu.hcmut.co3103.lms.exception.UserNotFoundException;
import vn.edu.hcmut.co3103.lms.repository.LmsSetupStateRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessSpecification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2025-02-01T00:00:00Z");
    private static final String LAST_ADMIN_MESSAGE = "Cannot demote or suspend the last active administrator";

    @Mock private LmsUserAccessRepository users;
    @Mock private LmsSetupStateRepository states;

    private AdminUserService service() {
        return new AdminUserService(users, states);
    }

    @ParameterizedTest
    @CsvSource({
            ", , , 0, 4, 2",
            "alice, , , 1, 2, 5",
            ", STUDENT, , 0, 2, 5",
            ", , SUSPENDED, 1, 2, 4",
            "alice, TUTOR, ACTIVE, 1, 2, 5",
            "123e4567-e89b-12d3-a456-426614174000, ADMINISTRATOR, ACTIVE, 0, 2, 2"
    })
    void listsUsersUsingFiltersAndPreservesPageMetadata(String query, LmsRole role, AccessStatus status,
                                                       int page, int size, long total) {
        var pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        var first = user(LmsRole.TUTOR, AccessStatus.ACTIVE);
        var second = user(LmsRole.STUDENT, AccessStatus.SUSPENDED);
        Specification<LmsUserAccess> filter = Specification.unrestricted();
        when(users.findAll(filter, pageable)).thenReturn(new PageImpl<>(List.of(first, second), pageable, total));

        try (var specifications = mockStatic(LmsUserAccessSpecification.class)) {
            specifications.when(() -> LmsUserAccessSpecification.filter(query, role, status)).thenReturn(filter);

            var response = service().getUsers(query, role, status, pageable);

            assertThat(response.content()).containsExactly(response(first), response(second));
            assertThat(response.page()).isEqualTo(page);
            assertThat(response.size()).isEqualTo(size);
            assertThat(response.totalElements()).isEqualTo(total);
            assertThat(response.totalPages()).isEqualTo((int) Math.ceil((double) total / size));
            assertThat(response.last()).isEqualTo((long) (page + 1) * size >= total);
            verify(users).findAll(filter, pageable);
            specifications.verify(() -> LmsUserAccessSpecification.filter(query, role, status));
        }
        verifyNoInteractions(states);
    }

    @Test
    void returnsAnEmptyPageWhenNoUsersMatch() {
        var pageable = PageRequest.of(3, 10);
        when(users.findAll(any(Specification.class), eq(pageable))).thenReturn(Page.empty(pageable));

        var response = service().getUsers("missing", null, null, pageable);

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isEqualTo(3);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
        assertThat(response.last()).isTrue();
        verifyNoInteractions(states);
    }

    @ParameterizedTest
    @ValueSource(strings = {"noSuchProperty", "role", "id", "userId.noSuchProperty"})
    void rejectsUnknownSortBeforeQueryingUsers(String property) {
        var pageable = PageRequest.of(0, 20, Sort.by("createdAt", property));

        assertThatThrownBy(() -> service().getUsers(null, null, null, pageable))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(users, states);
    }

    @Test
    void rejectsOverflowingOffsetBeforeQueryingUsers() {
        var pageable = PageRequest.of(Integer.MAX_VALUE, 20);

        assertThatThrownBy(() -> service().getUsers(null, null, null, pageable))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(users, states);
    }

    @Test
    void acceptsMaximumSupportedOffset() {
        var pageable = PageRequest.of(Integer.MAX_VALUE, 1);
        when(users.findAll(any(Specification.class), eq(pageable))).thenReturn(Page.empty(pageable));

        assertThat(service().getUsers(null, null, null, pageable).content()).isEmpty();

        verify(users).findAll(any(Specification.class), eq(pageable));
    }

    @ParameterizedTest
    @CsvSource({
            "STUDENT, ACTIVE, TUTOR, , TUTOR, ACTIVE",
            "TUTOR, ACTIVE, , SUSPENDED, TUTOR, SUSPENDED",
            "TUTOR, SUSPENDED, , ACTIVE, TUTOR, ACTIVE",
            "STUDENT, SUSPENDED, ADMINISTRATOR, ACTIVE, ADMINISTRATOR, ACTIVE",
            "ADMINISTRATOR, SUSPENDED, STUDENT, , STUDENT, SUSPENDED",
            "ADMINISTRATOR, SUSPENDED, , ACTIVE, ADMINISTRATOR, ACTIVE"
    })
    void updatesRequestedFieldsWithoutChangingIdentity(LmsRole initialRole, AccessStatus initialStatus,
                                                       LmsRole role, AccessStatus status,
                                                       LmsRole expectedRole, AccessStatus expectedStatus) {
        var target = stubTarget(initialRole, initialStatus);
        when(users.saveAndFlush(target)).thenAnswer(invocation -> {
            target.setUpdatedAt(UPDATED_AT.plusSeconds(1));
            return target;
        });

        var result = service().updateUserAccess(target.getUserId(), new UpdateUserAccessRequest(role, status),
                UUID.randomUUID());

        assertThat(result).isEqualTo(new AdminUserResponse(target.getUserId(), expectedRole, expectedStatus,
                CREATED_AT, UPDATED_AT.plusSeconds(1)));
        assertThat(target.getFullName()).isEqualTo("Existing User");
        assertThat(target.getEmail()).isEqualTo("existing@example.com");
        assertThat(target.getLastLoginAt()).isEqualTo(CREATED_AT);
        var order = inOrder(states, users);
        order.verify(states).lockGlobalState();
        order.verify(users).findById(target.getUserId());
        order.verify(users).saveAndFlush(target);
        verify(users, never()).countByLmsRoleAndAccessStatus(any(), any());
    }

    @Test
    void rejectsAnUnknownUserWithoutSavingOrCountingAdministrators() {
        stubLock();
        UUID missingId = UUID.randomUUID();
        when(users.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateUserAccess(missingId,
                new UpdateUserAccessRequest(LmsRole.TUTOR, null), UUID.randomUUID()))
                .isInstanceOf(UserNotFoundException.class);

        verify(users).findById(missingId);
        verifyNoMoreInteractions(users);
    }

    @ParameterizedTest
    @CsvSource({
            "STUDENT, , 1, false",
            "TUTOR, ACTIVE, 1, true",
            ", SUSPENDED, 1, false",
            "ADMINISTRATOR, SUSPENDED, 1, true",
            "STUDENT, SUSPENDED, 1, true",
            "TUTOR, , 0, false",
            ", SUSPENDED, 0, true"
    })
    void protectsTheLastActiveAdministratorBeforeMutatingTheEntity(LmsRole role, AccessStatus status,
                                                                   long activeAdmins, boolean self) {
        var target = stubTarget(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);
        when(users.countByLmsRoleAndAccessStatus(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE))
                .thenReturn(activeAdmins);
        UUID actor = self ? target.getUserId() : UUID.randomUUID();

        assertThatThrownBy(() -> service().updateUserAccess(target.getUserId(),
                new UpdateUserAccessRequest(role, status), actor))
                .isInstanceOf(LastAdminProtectionException.class).hasMessage(LAST_ADMIN_MESSAGE);

        assertThat(target.getLmsRole()).isEqualTo(LmsRole.ADMINISTRATOR);
        assertThat(target.getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(target.getUpdatedAt()).isEqualTo(UPDATED_AT);
        verify(users).countByLmsRoleAndAccessStatus(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);
        verify(users, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @CsvSource({
            "STUDENT, , STUDENT, ACTIVE, false",
            "TUTOR, , TUTOR, ACTIVE, true",
            ", SUSPENDED, ADMINISTRATOR, SUSPENDED, false",
            "ADMINISTRATOR, SUSPENDED, ADMINISTRATOR, SUSPENDED, true",
            "STUDENT, SUSPENDED, STUDENT, SUSPENDED, true"
    })
    void allowsAdministratorChangesIncludingSelfWhenAnotherActiveAdminExists(LmsRole role, AccessStatus status,
                                                                             LmsRole expectedRole,
                                                                             AccessStatus expectedStatus,
                                                                             boolean self) {
        var target = stubTarget(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);
        when(users.countByLmsRoleAndAccessStatus(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE)).thenReturn(2L);
        when(users.saveAndFlush(target)).thenReturn(target);
        UUID actor = self ? target.getUserId() : UUID.randomUUID();

        var result = service().updateUserAccess(target.getUserId(), new UpdateUserAccessRequest(role, status), actor);

        assertThat(result.role()).isEqualTo(expectedRole);
        assertThat(result.accessStatus()).isEqualTo(expectedStatus);
        verify(users).saveAndFlush(target);
    }

    @ParameterizedTest
    @CsvSource({",", "ADMINISTRATOR,", ", ACTIVE", "ADMINISTRATOR, ACTIVE"})
    void allowsUnchangedAccessForTheLastAdministrator(LmsRole role, AccessStatus status) {
        var target = stubTarget(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);
        when(users.saveAndFlush(target)).thenReturn(target);

        var result = service().updateUserAccess(target.getUserId(), new UpdateUserAccessRequest(role, status),
                target.getUserId());

        assertThat(result).isEqualTo(response(target));
        verify(users, never()).countByLmsRoleAndAccessStatus(any(), any());
    }

    @Test
    void failsClosedWhenTheDatabaseLockRowIsMissing() {
        when(states.lockGlobalState()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateUserAccess(UUID.randomUUID(),
                new UpdateUserAccessRequest(LmsRole.TUTOR, null), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("missing");

        verifyNoInteractions(users);
    }

    @Test
    void propagatesSaveFailure() {
        var target = stubTarget(LmsRole.STUDENT, AccessStatus.ACTIVE);
        var failure = new IllegalStateException("storage failure");
        when(users.saveAndFlush(target)).thenThrow(failure);

        assertThatThrownBy(() -> service().updateUserAccess(target.getUserId(),
                new UpdateUserAccessRequest(LmsRole.TUTOR, null), UUID.randomUUID())).isSameAs(failure);
    }

    private void stubLock() {
        when(states.lockGlobalState()).thenReturn(Optional.of(mock(LmsSetupState.class)));
    }

    private LmsUserAccess stubTarget(LmsRole role, AccessStatus status) {
        stubLock();
        var target = user(role, status);
        when(users.findById(target.getUserId())).thenReturn(Optional.of(target));
        return target;
    }

    private LmsUserAccess user(LmsRole role, AccessStatus status) {
        return new LmsUserAccess(UUID.randomUUID(), "Existing User", "existing@example.com", role, status,
                CREATED_AT, CREATED_AT, UPDATED_AT);
    }

    private AdminUserResponse response(LmsUserAccess user) {
        return new AdminUserResponse(user.getUserId(), user.getLmsRole(), user.getAccessStatus(),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
