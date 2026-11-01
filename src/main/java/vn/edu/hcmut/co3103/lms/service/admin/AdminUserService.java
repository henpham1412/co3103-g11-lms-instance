package vn.edu.hcmut.co3103.lms.service.admin;

import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.AuditLog;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.dto.admin.AdminUserPageResponse;
import vn.edu.hcmut.co3103.lms.dto.admin.AdminUserResponse;
import vn.edu.hcmut.co3103.lms.dto.admin.UpdateUserAccessRequest;
import vn.edu.hcmut.co3103.lms.exception.AdminAccessValidationException;
import vn.edu.hcmut.co3103.lms.exception.LastAdminProtectionException;
import vn.edu.hcmut.co3103.lms.exception.UserNotFoundException;
import vn.edu.hcmut.co3103.lms.repository.LmsSetupStateRepository;
import vn.edu.hcmut.co3103.lms.repository.AuditLogRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessSpecification;

import java.util.Set;
import java.util.UUID;

@Service
public class AdminUserService {

    private static final Set<String> SORTABLE_PROPERTIES = Set.of(
            "userId", "fullName", "email", "lmsRole", "accessStatus", "lastLoginAt", "createdAt", "updatedAt");

    private final LmsUserAccessRepository users;
    private final LmsSetupStateRepository states;
    private final AuditLogRepository auditLogs;

    public AdminUserService(LmsUserAccessRepository users, LmsSetupStateRepository states,
                            AuditLogRepository auditLogs) {
        this.users = users;
        this.states = states;
        this.auditLogs = auditLogs;
    }

    @Transactional(readOnly = true)
    public AdminUserPageResponse getUsers(String query, LmsRole role, AccessStatus status, Pageable pageable) {
        if (pageable.isPaged() && pageable.getOffset() > Integer.MAX_VALUE) {
            throw new AdminAccessValidationException();
        }
        for (var order : pageable.getSort()) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new AdminAccessValidationException();
            }
        }
        var page = users.findAll(LmsUserAccessSpecification.filter(query, role, status), pageable)
                .map(AdminUserService::toResponse);
        long externalPage = (long) page.getNumber() + 1;
        return new AdminUserPageResponse(page.getContent(), externalPage, page.getSize(),
                page.getTotalElements(), page.getTotalPages(), externalPage >= page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AdminUserResponse getUser(UUID userId) {
        return toResponse(users.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found")));
    }

    @Transactional
    public AdminUserResponse updateUserAccess(UUID userId, UpdateUserAccessRequest request, UUID currentAdminUserId) {
        // Share bootstrap's database lock so concurrent updates cannot remove the final active admins.
        // Load the actor and target only after locking to observe a preceding updater's committed changes.
        states.lockGlobalState()
                .orElseThrow(() -> new IllegalStateException("Administrator setup state is missing."));
        users.findById(currentAdminUserId)
                .filter(actor -> actor.getLmsRole() == LmsRole.ADMINISTRATOR
                        && actor.getAccessStatus() == AccessStatus.ACTIVE)
                .orElseThrow(() -> new AccessDeniedException(
                        "Access denied: acting administrator is no longer active with administrator role"));
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

        LmsRole previousRole = target.getLmsRole();
        AccessStatus previousStatus = target.getAccessStatus();
        if (request.role() != null) {
            target.setLmsRole(request.role());
        }
        if (request.accessStatus() != null) {
            target.setAccessStatus(request.accessStatus());
        }
        // Flush invokes @PreUpdate before the response captures updatedAt.
        LmsUserAccess saved = users.saveAndFlush(target);
        if (previousRole != saved.getLmsRole() || previousStatus != saved.getAccessStatus()) {
            auditLogs.saveAndFlush(new AuditLog(currentAdminUserId, saved, previousRole, previousStatus));
        }
        return toResponse(saved);
    }

    private static AdminUserResponse toResponse(LmsUserAccess user) {
        return new AdminUserResponse(user.getUserId(), user.getLmsRole(), user.getAccessStatus(),
                user.getCreatedAt(), user.getUpdatedAt(), user.getFullName(), user.getEmail(), user.getLastLoginAt());
    }
}
