package vn.edu.hcmut.co3103.lms.service.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsSetupState;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.dto.admin.AdminUserResponse;
import vn.edu.hcmut.co3103.lms.dto.admin.UpdateUserAccessRequest;
import vn.edu.hcmut.co3103.lms.exception.AdminAccessValidationException;
import vn.edu.hcmut.co3103.lms.exception.LastAdminProtectionException;
import vn.edu.hcmut.co3103.lms.exception.UserNotFoundException;
import vn.edu.hcmut.co3103.lms.repository.LmsSetupStateRepository;
import vn.edu.hcmut.co3103.lms.repository.AuditLogRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessSpecification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

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
    @Mock private AuditLogRepository auditLogs;

    private AdminUserService service() {
        return new AdminUserService(users, states, auditLogs);
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
        var deterministicPageable = pageable.withSort(pageable.getSort().and(Sort.by("userId")));
        var first = user(LmsRole.TUTOR, AccessStatus.ACTIVE);
        var second = user(LmsRole.STUDENT, AccessStatus.SUSPENDED);
        Specification<LmsUserAccess> filter = Specification.unrestricted();
        when(users.findAll(filter, deterministicPageable))
                .thenReturn(new PageImpl<>(List.of(first, second), deterministicPageable, total));

        try (var specifications = mockStatic(LmsUserAccessSpecification.class)) {
            specifications.when(() -> LmsUserAccessSpecification.filter(query, role, status)).thenReturn(filter);

            var response = service().getUsers(query, role, status, pageable);

            assertThat(response.content()).containsExactly(response(first), response(second));
            assertThat(response.page()).isEqualTo(page + 1L);
            assertThat(response.size()).isEqualTo(size);
            assertThat(response.totalElements()).isEqualTo(total);
            assertThat(response.totalPages()).isEqualTo((int) Math.ceil((double) total / size));
            assertThat(response.last()).isEqualTo((long) (page + 1) * size >= total);
            verify(users).findAll(filter, deterministicPageable);
            specifications.verify(() -> LmsUserAccessSpecification.filter(query, role, status));
        }
        verifyNoInteractions(states);
    }

    @Test
    void returnsAnEmptyPageWhenNoUsersMatch() {
        var pageable = PageRequest.of(3, 10);
        var deterministicPageable = pageable.withSort(Sort.by("userId"));
        when(users.findAll(any(Specification.class), eq(deterministicPageable)))
                .thenReturn(Page.empty(deterministicPageable));

        var response = service().getUsers("missing", null, null, pageable);

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isEqualTo(4);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
        assertThat(response.last()).isTrue();
        verifyNoInteractions(states);
    }

    static Stream<Arguments> deterministicSorts() {
        var byId = Sort.by("userId");
        var createdDescending = Sort.by("createdAt").descending();
        var multiple = Sort.by(Sort.Order.asc("lmsRole"), Sort.Order.desc("accessStatus"));
        var withOptions = Sort.by(Sort.Order.desc("fullName").ignoreCase().nullsLast());
        var idDescending = byId.descending();
        return Stream.of(
                Arguments.of(Sort.unsorted(), byId),
                Arguments.of(createdDescending, createdDescending.and(byId)),
                Arguments.of(multiple, multiple.and(byId)),
                Arguments.of(withOptions, withOptions.and(byId)),
                Arguments.of(idDescending, idDescending),
                Arguments.of(createdDescending.and(idDescending), createdDescending.and(idDescending)),
                Arguments.of(idDescending.and(multiple), idDescending.and(multiple)),
                Arguments.of(createdDescending.and(byId).and(multiple), createdDescending.and(byId).and(multiple)));
    }

    @ParameterizedTest
    @MethodSource("deterministicSorts")
    void usesUniqueOrderingWithoutChangingRequestedSortOrPagination(Sort requested, Sort expected) {
        for (boolean paged : List.of(true, false)) {
            Pageable pageable = paged ? PageRequest.of(2, 7, requested) : Pageable.unpaged(requested);
            Pageable deterministicPageable = paged ? PageRequest.of(2, 7, expected) : Pageable.unpaged(expected);
            when(users.findAll(any(Specification.class), eq(deterministicPageable)))
                    .thenReturn(Page.empty(deterministicPageable));

            service().getUsers(null, null, null, pageable);

            verify(users).findAll(any(Specification.class), eq(deterministicPageable));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"noSuchProperty", "role", "id", "userId.noSuchProperty"})
    void rejectsUnknownSortBeforeQueryingUsers(String property) {
        var pageable = PageRequest.of(0, 20, Sort.by("createdAt", property));

        assertThatThrownBy(() -> service().getUsers(null, null, null, pageable))
                .isInstanceOf(AdminAccessValidationException.class);

        verifyNoInteractions(users, states);
    }

    @Test
    void rejectsOverflowingOffsetBeforeQueryingUsers() {
        var pageable = PageRequest.of(Integer.MAX_VALUE, 20);

        assertThatThrownBy(() -> service().getUsers(null, null, null, pageable))
                .isInstanceOf(AdminAccessValidationException.class);

        verifyNoInteractions(users, states);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\0", "a\0b", "\0alice", "alice\0", " \t\0\n ", "a\0\0b"})
    void rejectsNulSearchBeforeQueryingUsers(String query) {
        for (Pageable pageable : List.of(PageRequest.of(0, 2000), Pageable.unpaged())) {
            assertThatThrownBy(() -> service().getUsers(query, null, null, pageable))
                    .isInstanceOf(AdminAccessValidationException.class);
            assertThatThrownBy(() -> service().getUsers(query, LmsRole.TUTOR, AccessStatus.ACTIVE, pageable))
                    .isInstanceOf(AdminAccessValidationException.class);
        }
        verifyNoInteractions(users, states, auditLogs);
    }

    @Test
    void acceptsMaximumSupportedOffset() {
        var pageable = PageRequest.of(Integer.MAX_VALUE, 1);
        var deterministicPageable = pageable.withSort(Sort.by("userId"));
        when(users.findAll(any(Specification.class), eq(deterministicPageable)))
                .thenReturn(Page.empty(deterministicPageable));

        var result = service().getUsers(null, null, null, pageable);
        assertThat(result.content()).isEmpty();
        assertThat(result.last()).isTrue();
        assertThat(result.page()).isEqualTo(2147483648L);

        verify(users).findAll(any(Specification.class), eq(deterministicPageable));
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
        UUID actor = stubActor();
        when(users.saveAndFlush(target)).thenAnswer(invocation -> {
            target.setUpdatedAt(UPDATED_AT.plusSeconds(1));
            return target;
        });

        var result = service().updateUserAccess(target.getUserId(), new UpdateUserAccessRequest(role, status),
                actor);

        assertThat(result).isEqualTo(new AdminUserResponse(target.getUserId(), expectedRole, expectedStatus,
                CREATED_AT, UPDATED_AT.plusSeconds(1), "Existing User", "existing@example.com", CREATED_AT));
        assertThat(target.getFullName()).isEqualTo("Existing User");
        assertThat(target.getEmail()).isEqualTo("existing@example.com");
        assertThat(target.getLastLoginAt()).isEqualTo(CREATED_AT);
        var order = inOrder(states, users);
        order.verify(states).lockGlobalState();
        order.verify(users).findById(actor);
        order.verify(users).findById(target.getUserId());
        order.verify(users).saveAndFlush(target);
        verify(users, never()).countByLmsRoleAndAccessStatus(any(), any());
    }

    @Test
    void rejectsAnUnknownUserWithoutSavingOrCountingAdministrators() {
        stubLock();
        UUID actor = stubActor();
        UUID missingId = UUID.randomUUID();
        when(users.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateUserAccess(missingId,
                new UpdateUserAccessRequest(LmsRole.TUTOR, null), actor))
                .isInstanceOf(UserNotFoundException.class);

        verify(users).findById(actor);
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
        UUID actor = self ? target.getUserId() : stubActor();

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
        UUID actor = self ? target.getUserId() : stubActor();

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
        UUID actor = stubActor();
        var failure = new IllegalStateException("storage failure");
        when(users.saveAndFlush(target)).thenThrow(failure);

        assertThatThrownBy(() -> service().updateUserAccess(target.getUserId(),
                new UpdateUserAccessRequest(LmsRole.TUTOR, null), actor)).isSameAs(failure);
    }

    @ParameterizedTest
    @CsvSource({",", "STUDENT,ACTIVE", "TUTOR,ACTIVE", "ADMINISTRATOR,SUSPENDED",
            "STUDENT,SUSPENDED", "TUTOR,SUSPENDED"})
    void rejectsMissingOrRevokedActorUnderLockBeforeReadingTarget(LmsRole role, AccessStatus status) {
        stubLock();
        UUID actorId = UUID.randomUUID();
        when(users.findById(actorId)).thenReturn(role == null ? Optional.empty() : Optional.of(user(role, status)));

        assertThatThrownBy(() -> service().updateUserAccess(UUID.randomUUID(),
                new UpdateUserAccessRequest(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE), actorId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Access denied: acting administrator is no longer active with administrator role");

        var order = inOrder(states, users);
        order.verify(states).lockGlobalState();
        order.verify(users).findById(actorId);
        verifyNoMoreInteractions(states, users);
        verifyNoInteractions(auditLogs);
    }

    private UUID stubActor() {
        var actor = user(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);
        when(users.findById(actor.getUserId())).thenReturn(Optional.of(actor));
        return actor.getUserId();
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
                user.getCreatedAt(), user.getUpdatedAt(), user.getFullName(), user.getEmail(), user.getLastLoginAt());
    }
}
