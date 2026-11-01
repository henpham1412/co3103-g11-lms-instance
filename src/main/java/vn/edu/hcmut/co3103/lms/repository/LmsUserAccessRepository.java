package vn.edu.hcmut.co3103.lms.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LmsUserAccessRepository extends JpaRepository<LmsUserAccess, UUID> {

    long countByLmsRole(LmsRole lmsRole);

    boolean existsByLmsRole(LmsRole lmsRole);

    boolean existsByLmsRoleAndAccessStatus(LmsRole lmsRole, AccessStatus accessStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LmsUserAccess> findFirstByLmsRoleOrderByCreatedAtAscUserIdAsc(LmsRole role);

    List<LmsUserAccess> findAllByEmail(String email);
}
