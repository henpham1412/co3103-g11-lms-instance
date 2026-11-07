package vn.edu.hcmut.co3103.lms.repository.assessment;

import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import vn.edu.hcmut.co3103.lms.entity.Quiz;

import java.util.Optional;
import java.util.UUID;

public interface QuizRepository extends JpaRepository<Quiz, UUID> {
    Optional<Quiz> findByActivity_ActivityId(UUID activityId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Quiz> findLockedById(UUID quizId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Quiz> findLockedByActivity_ActivityId(UUID activityId);
}