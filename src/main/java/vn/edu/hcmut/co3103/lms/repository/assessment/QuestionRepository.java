package vn.edu.hcmut.co3103.lms.repository.assessment;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.Question;

import java.util.UUID;
import java.util.List;
import java.util.Optional;

public interface QuestionRepository extends JpaRepository<Question, UUID> {
    Optional<Question> findByIdAndQuiz_Id(UUID questionId, UUID quizId);
    List<Question> findByQuiz_IdOrderByOrderIndex(UUID quizId);
    boolean existsByQuiz_IdAndOrderIndexAndIdNot(UUID quizId, Integer orderIndex, UUID questionId);
    boolean existsByQuiz_IdAndOrderIndex(UUID quizId, Integer orderIndex);
}
