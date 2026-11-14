package vn.edu.hcmut.co3103.lms.repository.assessment;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.hcmut.co3103.lms.entity.Submission;

import java.util.Optional;
import java.util.UUID;

public interface SubmissionRepository extends JpaRepository<Submission, UUID> {
    Optional<Submission> findByAssignmentIdAndClassMemberId(UUID assignmentId, UUID classMemberId);

    @Query("select a.activityId from Submission s, Assignment a where s.submissionId=:submissionId and s.assignmentId=a.assignmentId")
    Optional<UUID> findActivityIdBySubmissionId(@Param("submissionId") UUID submissionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Submission s where s.assignmentId = :assignmentId and s.classMemberId = :classMemberId")
    Optional<Submission> findLockedByAssignmentIdAndClassMemberId(
            @Param("assignmentId") UUID assignmentId,
            @Param("classMemberId") UUID classMemberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Submission s where s.submissionId = :submissionId")
    Optional<Submission> findLockedBySubmissionId(@Param("submissionId") UUID submissionId);

    @Query("""
            select s from Submission s
            where s.assignmentId = :assignmentId
              and (:status is null or s.status = :status)
            order by s.submittedAt desc
            """)
    Page<Submission> findByAssignmentIdAndStatus(
            @Param("assignmentId") UUID assignmentId,
            @Param("status") Submission.Status status,
            Pageable pageable);
}
