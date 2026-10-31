package vn.edu.hcmut.co3103.lms.repository.assessment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;

import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, UUID> {
    boolean existsByQuiz_Id(UUID quizId);
    List<QuizAttempt> findByQuiz_IdAndStatus(UUID quizId, String status);
    List<QuizAttempt> findByQuiz_IdAndClassMember_ClassMemberIdOrderByAttemptNumber(UUID quizId, UUID classMemberId);
    long countByQuiz_IdAndClassMember_ClassMemberId(UUID quizId, UUID classMemberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    java.util.Optional<QuizAttempt> findLockedById(UUID attemptId);

    @Query(value = """
            SELECT qa.attempt_id
            FROM quiz_attempt qa
            JOIN quiz q ON q.quiz_id = qa.quiz_id
            WHERE qa.status = 'IN_PROGRESS'
              AND (qa.started_at + (q.time_limit_minutes * INTERVAL '1 minute') <= :asOf
                   OR q.deadline <= :asOf)
            ORDER BY LEAST(qa.started_at + (q.time_limit_minutes * INTERVAL '1 minute'), q.deadline)
            LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findExpiredAttemptIds(@Param("asOf") OffsetDateTime asOf,
                                    @Param("batchSize") int batchSize);
}
