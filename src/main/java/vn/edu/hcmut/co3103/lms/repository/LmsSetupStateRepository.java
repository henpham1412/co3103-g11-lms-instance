package vn.edu.hcmut.co3103.lms.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import vn.edu.hcmut.co3103.lms.domain.LmsSetupState;

import java.util.Optional;

public interface LmsSetupStateRepository extends JpaRepository<LmsSetupState, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from LmsSetupState state where state.id = 'GLOBAL'")
    Optional<LmsSetupState> lockGlobalState();
}
