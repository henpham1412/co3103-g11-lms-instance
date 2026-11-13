package vn.edu.hcmut.co3103.lms.repository.assessment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.hcmut.co3103.lms.entity.Assignment;

import java.util.Optional;
import java.util.UUID;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {
    Optional<Assignment> findByActivityId(UUID activityId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Assignment a where a.assignmentId = :assignmentId")
    Optional<Assignment> findLockedByAssignmentId(@Param("assignmentId") UUID assignmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Assignment a where a.activityId = :activityId")
    Optional<Assignment> findLockedByActivityId(@Param("activityId") UUID activityId);
}
