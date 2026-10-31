package vn.edu.hcmut.co3103.lms.repository.assessment;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;

import java.util.List;
import java.util.UUID;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, UUID> {
    boolean existsByQuiz_Id(UUID quizId);
    List<QuizAttempt> findByQuiz_IdAndStatus(UUID quizId, String status);
}
