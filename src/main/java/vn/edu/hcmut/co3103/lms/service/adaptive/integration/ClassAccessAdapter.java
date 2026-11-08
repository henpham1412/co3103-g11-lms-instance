package vn.edu.hcmut.co3103.lms.service.adaptive.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * LMS-2/LMS-3 adapter enforcing owning-Tutor and active-Student class read access,
 * and owning-Tutor pessimistic write locking on ACTIVE classes for LMS-8 operations.
 */
@Component
public class ClassAccessAdapter implements ClassAccess {

    private static final ThreadLocal<UUID> ACTOR_OVERRIDE = new ThreadLocal<>();

    private final ClassRepository classRepository;
    private final ClassMemberRepository classMemberRepository;
    private final LmsUserAccessRepository userAccessRepository;

    public ClassAccessAdapter(ClassRepository classRepository,
                              LmsUserAccessRepository userAccessRepository) {
        this(classRepository, null, userAccessRepository);
    }

    @Autowired
    public ClassAccessAdapter(ClassRepository classRepository,
                              ClassMemberRepository classMemberRepository,
                              LmsUserAccessRepository userAccessRepository) {
        this.classRepository = classRepository;
        this.classMemberRepository = classMemberRepository;
        this.userAccessRepository = userAccessRepository;
    }

    /**
     * Binds an explicit actor ID for internal service-to-service hooks (such as LMS-5
     * Question write/creation services) when invoked outside a Spring Security HTTP filter.
     */
    public static <T> T withActor(UUID actorId, Supplier<T> action) {
        UUID previous = ACTOR_OVERRIDE.get();
        ACTOR_OVERRIDE.set(actorId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                ACTOR_OVERRIDE.remove();
            } else {
                ACTOR_OVERRIDE.set(previous);
            }
        }
    }

    public static void withActor(UUID actorId, Runnable action) {
        withActor(actorId, () -> {
            action.run();
            return null;
        });
    }

    @Override
    public void requireReadAccess(UUID classId) {
        if (classId == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Class targetClass = classRepository.findById(classId)
                .orElseThrow(() -> new AppException(ErrorCode.CLASS_NOT_FOUND));
        LmsUserAccess actor = requireActiveUser();
        if (actor.getLmsRole() == LmsRole.TUTOR) {
            if (!targetClass.getTutorId().equals(actor.getUserId())) {
                throw new AppException(ErrorCode.CLASS_ACCESS_DENIED);
            }
            return;
        }
        if (actor.getLmsRole() == LmsRole.STUDENT) {
            boolean activeMember = classMemberRepository != null
                    && classMemberRepository.existsByClassIdAndStudentIdAndStatus(
                            classId,
                            actor.getUserId(),
                            ClassMember.MembershipStatus.ACTIVE
                    );
            if (!activeMember) {
                throw new AppException(ErrorCode.CLASS_ACCESS_DENIED);
            }
            return;
        }
        throw new AppException(ErrorCode.CLASS_ACCESS_DENIED);
    }

    @Override
    public void lockForWrite(UUID classId) {
        if (classId == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Class targetClass = classRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new AppException(ErrorCode.CLASS_NOT_FOUND));
        requireOwningActiveTutor(targetClass);
        if (targetClass.getStatus() != Class.ClassStatus.ACTIVE) {
            throw new AppException(ErrorCode.RESOURCE_STATE_CONFLICT);
        }
    }

    private void requireOwningActiveTutor(Class targetClass) {
        LmsUserAccess actor = requireActiveUser();
        if (actor.getLmsRole() != LmsRole.TUTOR || !targetClass.getTutorId().equals(actor.getUserId())) {
            throw new AppException(ErrorCode.CLASS_ACCESS_DENIED);
        }
    }

    private LmsUserAccess requireActiveUser() {
        UUID actorId = currentActorId();
        return userAccessRepository.findById(actorId)
                .filter(user -> user.getAccessStatus() == AccessStatus.ACTIVE)
                .orElseThrow(() -> new AppException(ErrorCode.CLASS_ACCESS_DENIED));
    }

    private UUID currentActorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof Jwt jwt && jwt.getSubject() != null) {
                try {
                    return UUID.fromString(jwt.getSubject());
                } catch (IllegalArgumentException ignored) {
                    throw new AppException(ErrorCode.CLASS_ACCESS_DENIED);
                }
            }
            String name = authentication.getName();
            if (name != null) {
                try {
                    return UUID.fromString(name);
                } catch (IllegalArgumentException ignored) {
                    // Fall through to actor override if present
                }
            }
        }
        UUID override = ACTOR_OVERRIDE.get();
        if (override != null) {
            return override;
        }
        throw new AppException(ErrorCode.CLASS_ACCESS_DENIED);
    }
}
