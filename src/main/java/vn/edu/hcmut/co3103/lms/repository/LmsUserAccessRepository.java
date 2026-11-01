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

import java.math.BigDecimal;
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

    default int synchronizeIdentity(UUID userId, String fullName, String email, Instant issuedAt, Instant now) {
        // No issuance means there is no ordered profile or login event to consume.
        return issuedAt == null ? 0 : synchronizeIssuedIdentity(userId, fullName, email,
                BigDecimal.valueOf(issuedAt.getEpochSecond()).add(BigDecimal.valueOf(issuedAt.getNano(), 9)), now);
    }

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE {h-schema}lms_user_access u
            SET full_name = CASE WHEN u.last_synced_profile_iat IS NULL OR u.last_synced_profile_iat <= :issuedAt
                    THEN COALESCE(:fullName, u.full_name) ELSE u.full_name END,
                email = CASE WHEN u.last_synced_profile_iat IS NULL OR u.last_synced_profile_iat <= :issuedAt
                    THEN COALESCE(:email, u.email) ELSE u.email END,
                last_synced_profile_iat = CASE WHEN (:fullName IS NOT NULL OR :email IS NOT NULL)
                    AND (u.last_synced_profile_iat IS NULL OR u.last_synced_profile_iat <= :issuedAt)
                    THEN :issuedAt ELSE u.last_synced_profile_iat END,
                last_login_at = CASE WHEN u.access_status = 'ACTIVE'
                    AND (u.last_login_token_iat IS NULL OR u.last_login_token_iat < :issuedAt)
                    THEN :now ELSE u.last_login_at END,
                last_login_token_iat = CASE WHEN u.access_status = 'ACTIVE'
                    AND (u.last_login_token_iat IS NULL OR u.last_login_token_iat < :issuedAt)
                    THEN :issuedAt ELSE u.last_login_token_iat END,
                updated_at = :now
            WHERE u.user_id = :userId
                AND ((u.access_status = 'ACTIVE'
                        AND (u.last_login_token_iat IS NULL OR u.last_login_token_iat < :issuedAt))
                    OR ((:fullName IS NOT NULL OR :email IS NOT NULL)
                        AND (u.last_synced_profile_iat IS NULL OR u.last_synced_profile_iat < :issuedAt
                            OR (u.last_synced_profile_iat = :issuedAt
                                AND ((:fullName IS NOT NULL AND u.full_name <> :fullName)
                                    OR (:email IS NOT NULL AND u.email <> :email))))))
            """, nativeQuery = true)
    int synchronizeIssuedIdentity(UUID userId, String fullName, String email, BigDecimal issuedAt, Instant now);
}
