package vn.edu.hcmut.co3103.lms.repository.assessment;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.AttemptAnswerOption;
import vn.edu.hcmut.co3103.lms.entity.AttemptAnswerOptionId;

import java.util.List;
import java.util.UUID;

public interface AttemptAnswerOptionRepository extends JpaRepository<AttemptAnswerOption, AttemptAnswerOptionId> {
    List<AttemptAnswerOption> findByAnswer_Id(UUID answerId);
}