package vn.edu.hcmut.co3103.lms.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LmsUserAccessRepository extends JpaRepository<LmsUserAccess, UUID>,
        JpaSpecificationExecutor<LmsUserAccess> {

    long countByLmsRole(LmsRole lmsRole);

    long countByLmsRoleAndAccessStatus(LmsRole lmsRole, AccessStatus accessStatus);

    boolean existsByLmsRole(LmsRole lmsRole);

    boolean existsByLmsRoleAndAccessStatus(LmsRole lmsRole, AccessStatus accessStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LmsUserAccess> findFirstByLmsRoleOrderByCreatedAtAscUserIdAsc(LmsRole role);

    List<LmsUserAccess> findAllByEmail(String email);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE LmsUserAccess u
            SET u.fullName = COALESCE(:fullName, u.fullName),
                u.email = COALESCE(:email, u.email),
                u.lastLoginAt = CASE WHEN u.accessStatus = vn.edu.hcmut.co3103.lms.domain.AccessStatus.ACTIVE
                    AND (u.lastLoginAt IS NULL OR u.lastLoginAt < :issuedAt)
                    THEN :now ELSE u.lastLoginAt END,
                u.updatedAt = :now
            WHERE u.userId = :userId
            """)
    int synchronizeIdentity(UUID userId, String fullName, String email, Instant issuedAt, Instant now);
}
