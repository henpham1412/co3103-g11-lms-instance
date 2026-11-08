package vn.edu.hcmut.co3103.lms.service.assessment;

import org.junit.jupiter.api.Test;
import vn.edu.hcmut.co3103.lms.dto.request.QuestionCreateRequest;
import vn.edu.hcmut.co3103.lms.dto.response.QuestionResponse;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuizQuestionServiceCreateTest {
    private final QuizRepository quizRepository = mock(QuizRepository.class);
    private final QuestionRepository questionRepository = mock(QuestionRepository.class);
    private final QuestionOptionRepository optionRepository = mock(QuestionOptionRepository.class);
    private final QuizAttemptRepository attemptRepository = mock(QuizAttemptRepository.class);
    private final QuizConfigurationAccessService accessService = mock(QuizConfigurationAccessService.class);
    private final QuizGradingService gradingService = mock(QuizGradingService.class);

    @Test
    void createDefaultsOmittedPointsDeductedToZero() {
        UUID activityId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        Activity activity = mock(Activity.class);
        when(quizRepository.findLockedByActivity_ActivityId(activityId)).thenReturn(java.util.Optional.of(quiz));
        when(attemptRepository.existsByQuiz_Id(quiz.getId())).thenReturn(false);
        when(questionRepository.existsByQuiz_IdAndOrderIndex(quiz.getId(), 0)).thenReturn(false);
        when(questionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(optionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        QuestionCreateRequest request = new QuestionCreateRequest("MCQ_SINGLE", "Question", BigDecimal.ONE,
                null, 0, List.of(
                new QuestionCreateRequest.Option("Correct", true, 0),
                new QuestionCreateRequest.Option("Incorrect", false, 1)), null);

        QuestionResponse response = new QuizQuestionService(mock(AssessmentLocks.class), quizRepository, questionRepository, optionRepository,
                attemptRepository, accessService, gradingService).addQuestion(activityId, userId, request);

        assertEquals(BigDecimal.ZERO, response.pointsDeducted());
    }
}
