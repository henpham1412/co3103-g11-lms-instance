package vn.edu.hcmut.co3103.lms.repository.assessment;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.AttemptAnswer;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface AttemptAnswerRepository extends JpaRepository<AttemptAnswer, UUID> {
    Optional<AttemptAnswer> findByAttempt_IdAndQuestion_Id(UUID attemptId, UUID questionId);
    List<AttemptAnswer> findByAttempt_Id(UUID attemptId);
}
