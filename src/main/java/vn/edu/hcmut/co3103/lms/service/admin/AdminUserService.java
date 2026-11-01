package vn.edu.hcmut.co3103.lms.service.admin;

import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.dto.admin.AdminUserPageResponse;
import vn.edu.hcmut.co3103.lms.dto.admin.AdminUserResponse;
import vn.edu.hcmut.co3103.lms.dto.admin.UpdateUserAccessRequest;
import vn.edu.hcmut.co3103.lms.exception.LastAdminProtectionException;
import vn.edu.hcmut.co3103.lms.exception.UserNotFoundException;
import vn.edu.hcmut.co3103.lms.repository.LmsSetupStateRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessSpecification;

import java.util.UUID;

@Service
public class AdminUserService {

    private final LmsUserAccessRepository users;
    private final LmsSetupStateRepository states;

    public AdminUserService(LmsUserAccessRepository users, LmsSetupStateRepository states) {
        this.users = users;
        this.states = states;
    }

    @Transactional(readOnly = true)
    public AdminUserPageResponse getUsers(String query, LmsRole role, AccessStatus status, Pageable pageable) {
        var page = users.findAll(LmsUserAccessSpecification.filter(query, role, status), pageable)
                .map(AdminUserService::toResponse);
        return new AdminUserPageResponse(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isLast());
    }

    @Transactional
    public AdminUserResponse updateUserAccess(UUID userId, UpdateUserAccessRequest request, UUID currentAdminUserId) {
        // Share bootstrap's database lock so concurrent updates cannot remove the final active admins.
        // Acquire it before loading the target to observe changes committed by a preceding updater.
        states.lockGlobalState()
                .orElseThrow(() -> new IllegalStateException("Administrator setup state is missing."));
        LmsUserAccess target = users.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        boolean removesActiveAdmin = target.getLmsRole() == LmsRole.ADMINISTRATOR
                && target.getAccessStatus() == AccessStatus.ACTIVE
                && ((request.role() != null && request.role() != LmsRole.ADMINISTRATOR)
                    || request.accessStatus() == AccessStatus.SUSPENDED);
        // The same protection applies when currentAdminUserId equals userId; self-updates are otherwise allowed.
        if (removesActiveAdmin
                && users.countByLmsRoleAndAccessStatus(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE) <= 1) {
            throw new LastAdminProtectionException("Cannot demote or suspend the last active administrator");
        }

        if (request.role() != null) {
            target.setLmsRole(request.role());
        }
        if (request.accessStatus() != null) {
            target.setAccessStatus(request.accessStatus());
        }
        // Flush invokes @PreUpdate before the response captures updatedAt.
        return toResponse(users.saveAndFlush(target));
    }

    private static AdminUserResponse toResponse(LmsUserAccess user) {
        return new AdminUserResponse(user.getUserId(), user.getLmsRole(), user.getAccessStatus(),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
