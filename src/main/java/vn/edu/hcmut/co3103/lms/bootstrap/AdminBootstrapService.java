package vn.edu.hcmut.co3103.lms.bootstrap;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsSetupState;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.repository.LmsSetupStateRepository;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;

import java.util.UUID;

public class AdminBootstrapService {

    public enum Outcome { CREATED, EXISTING_ADMINISTRATOR }

    private final LmsSetupStateRepository states;
    private final LmsUserAccessRepository users;
    private final Validator validator;

    public AdminBootstrapService(LmsSetupStateRepository states, LmsUserAccessRepository users,
                                 Validator validator) {
        this.states = states;
        this.users = users;
        this.validator = validator;
    }

    @Transactional
    public Outcome bootstrap(UUID userId, String fullName, String email) {
        if (userId == null) {
            throw new IllegalArgumentException("Administrator user-id is required.");
        }
        LmsSetupState state = states.lockGlobalState()
                .orElseThrow(() -> new IllegalStateException("Administrator setup state is missing."));
        if (state.isCompleted()) {
            throw new IllegalStateException("Administrator setup is already complete.");
        }
        if (users.existsByLmsRoleAndAccessStatus(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE)) {
            state.complete(null);
            // Return normally so this permanent lockout commits before the CLI reports refusal.
            return Outcome.EXISTING_ADMINISTRATOR;
        }

        LmsUserAccess user = users.findById(userId).orElseGet(() ->
                new LmsUserAccess(userId, fullName, email, LmsRole.ADMINISTRATOR,
                        AccessStatus.ACTIVE, null, null, null));
        user.setLmsRole(LmsRole.ADMINISTRATOR);
        user.setAccessStatus(AccessStatus.ACTIVE);
        var violations = validator.validate(user);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException("Invalid administrator identity projection.", violations);
        }
        // Flush the user before referencing it from the completion marker's foreign key.
        users.saveAndFlush(user);
        state.complete(userId);
        return Outcome.CREATED;
    }
}
