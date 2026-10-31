package vn.edu.hcmut.co3103.lms.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;

import java.util.List;
import java.util.UUID;

public interface LmsUserAccessRepository extends JpaRepository<LmsUserAccess, UUID> {

    long countByLmsRole(LmsRole lmsRole);

    boolean existsByLmsRole(LmsRole lmsRole);

    boolean existsByLmsRoleAndAccessStatus(LmsRole lmsRole, AccessStatus accessStatus);

    List<LmsUserAccess> findAllByEmail(String email);
}
