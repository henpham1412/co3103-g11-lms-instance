package vn.edu.hcmut.co3103.lms.repository.assessment;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.QuestionOption;

import java.util.UUID;
import java.util.List;

public interface QuestionOptionRepository extends JpaRepository<QuestionOption, UUID> {
    List<QuestionOption> findByQuestion_IdOrderByOrderIndex(UUID questionId);
    void deleteByQuestion_Id(UUID questionId);
}