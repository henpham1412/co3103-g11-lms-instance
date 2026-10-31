package vn.edu.hcmut.co3103.lms.service.assessment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.entity.AttemptAnswer;
import vn.edu.hcmut.co3103.lms.entity.Question;
import vn.edu.hcmut.co3103.lms.entity.QuestionOption;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;
import vn.edu.hcmut.co3103.lms.repository.assessment.AttemptAnswerOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.AttemptAnswerRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QuizGradingService {
    private final QuizAttemptRepository attemptRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final AttemptAnswerRepository answerRepository;
    private final AttemptAnswerOptionRepository selectedOptionRepository;

    @Transactional
    public void recalculateSubmittedAttempts(Quiz quiz) {
        for (QuizAttempt attempt : attemptRepository.findByQuiz_IdAndStatus(quiz.getId(), "SUBMITTED")) {
            gradeAttempt(attempt);
        }
    }

    public void gradeAttempt(QuizAttempt attempt) {
        var questions = questionRepository.findByQuiz_IdOrderByOrderIndex(attempt.getQuiz().getId());
        BigDecimal totalEarnable = questions.stream().map(Question::getPointsAwarded)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal scored = BigDecimal.ZERO;

        for (Question question : questions) {
            AttemptAnswer answer = answerRepository.findByAttempt_IdAndQuestion_Id(
                    attempt.getId(), question.getId()).orElse(null);
            if (answer == null) continue;

            Set<UUID> selected = selectedOptionRepository.findByAnswer_Id(answer.getId()).stream()
                    .map(answerOption -> answerOption.getOption().getId()).collect(Collectors.toSet());
            Set<UUID> correctOptions = optionRepository.findByQuestion_IdOrderByOrderIndex(question.getId()).stream()
                    .filter(QuestionOption::getIsCorrect).map(QuestionOption::getId).collect(Collectors.toSet());
            boolean correct = !selected.isEmpty() && selected.equals(correctOptions);
            BigDecimal earned = selected.isEmpty() ? BigDecimal.ZERO
                    : correct ? question.getPointsAwarded() : question.getPointsDeducted().negate();
            answer.setIsCorrect(correct);
            answer.setPointsEarned(earned);
            scored = scored.add(earned);
        }

        BigDecimal percentage = totalEarnable.signum() <= 0 ? BigDecimal.ZERO
                : scored.multiply(BigDecimal.valueOf(100))
                        .divide(totalEarnable, 2, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
        attempt.setScore(percentage);
        attempt.setGradedAt(OffsetDateTime.now());
    }
}

