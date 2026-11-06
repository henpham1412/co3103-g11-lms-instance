package vn.edu.hcmut.co3103.lms.service.assessment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.QuestionUpdateRequest;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Question;
import vn.edu.hcmut.co3103.lms.entity.QuestionOption;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuizQuestionServiceOptionUpdateTest {
    private final QuizRepository quizRepository = mock(QuizRepository.class);
    private final QuestionRepository questionRepository = mock(QuestionRepository.class);
    private final QuestionOptionRepository optionRepository = mock(QuestionOptionRepository.class);
    private final QuizAttemptRepository attemptRepository = mock(QuizAttemptRepository.class);

    private final UUID quizId = UUID.randomUUID();
    private final UUID questionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID keptOptionId = UUID.randomUUID();
    private final UUID removedOptionId = UUID.randomUUID();
    private final UUID survivingOptionId = UUID.randomUUID();

    private QuizQuestionService service;
    private QuestionOption keptOption;
    private QuestionOption removedOption;
    private QuestionOption survivingOption;
    private final List<List<QuestionOption>> savedBatches = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new QuizQuestionService(quizRepository, questionRepository, optionRepository,
                attemptRepository, mock(QuizConfigurationAccessService.class), mock(QuizGradingService.class));

        Activity activity = mock(Activity.class);
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setActivity(activity);

        Question question = new Question();
        question.setId(questionId);
        question.setQuiz(quiz);
        question.setQuestionType("MCQ_SINGLE");
        question.setContent("Question");
        question.setPointsAwarded(BigDecimal.ONE);
        question.setPointsDeducted(BigDecimal.ZERO);
        question.setOrderIndex(0);

        keptOption = option(keptOptionId, question, "Keep", true, 0);
        removedOption = option(removedOptionId, question, "Remove", false, 1);
        survivingOption = option(survivingOptionId, question, "Survive", false, 2);

        when(questionRepository.findById(questionId)).thenReturn(Optional.of(question));
        when(quizRepository.findLockedById(quizId)).thenReturn(Optional.of(quiz));
        when(questionRepository.existsByQuiz_IdAndOrderIndexAndIdNot(quizId, 0, questionId)).thenReturn(false);
        when(attemptRepository.existsByQuiz_Id(quizId)).thenReturn(false);
        when(optionRepository.findByQuestion_IdOrderByOrderIndex(questionId))
                .thenReturn(new ArrayList<>(List.of(keptOption, removedOption, survivingOption)));
        doAnswer(invocation -> {
            List<QuestionOption> batch = new ArrayList<>();
            ((Iterable<?>) invocation.getArgument(0))
                    .forEach(option -> batch.add(QuestionOption.class.cast(option)));
            savedBatches.add(batch);
            return batch;
        }).when(optionRepository).saveAll(any());
    }

    @Test
    void updateDeletesRemovedOptionAndReordersOnlySurvivorsBeforeAddingNewOption() {
        QuestionUpdateRequest request = new QuestionUpdateRequest();
        request.setOptions(List.of(
                new QuestionUpdateRequest.Option(null, "New", false, 0),
                new QuestionUpdateRequest.Option(survivingOptionId, "Survive edited", false, 1),
                new QuestionUpdateRequest.Option(keptOptionId, "Keep edited", true, 2)));

        service.updateQuestion(questionId, userId, request);

        verify(optionRepository).deleteAll(List.of(removedOption));
        verify(optionRepository, times(2)).saveAll(any());

        assertEquals(2, savedBatches.get(0).size());
        assertEquals(keptOptionId, savedBatches.get(0).get(0).getId());
        assertEquals(survivingOptionId, savedBatches.get(0).get(1).getId());
        assertFalse(savedBatches.get(0).contains(removedOption));
        assertEquals(3, savedBatches.get(1).size());
        assertFalse(savedBatches.get(1).contains(removedOption));
        assertEquals(survivingOptionId, savedBatches.get(1).get(1).getId());
        assertEquals(keptOptionId, savedBatches.get(1).get(2).getId());
        assertEquals(2, keptOption.getOrderIndex());
        assertEquals("Keep edited", keptOption.getContent());
        assertEquals(1, survivingOption.getOrderIndex());
        assertEquals("Survive edited", survivingOption.getContent());

        QuestionOption added = savedBatches.get(1).stream()
                .filter(option -> option.getId() == null).findFirst().orElseThrow();
        assertEquals(0, added.getOrderIndex());
        assertEquals("New", added.getContent());
    }

    @Test
    void updateStillRejectsRemovingOptionsAfterQuizHasAttempts() {
        when(attemptRepository.existsByQuiz_Id(quizId)).thenReturn(true);
        QuestionUpdateRequest request = new QuestionUpdateRequest();
        request.setOptions(List.of(
                new QuestionUpdateRequest.Option(keptOptionId, "Keep", true, 0),
                new QuestionUpdateRequest.Option(survivingOptionId, "Survive", false, 1)));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.updateQuestion(questionId, userId, request));

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        verify(optionRepository, never()).deleteAll(any());
        verify(optionRepository, never()).saveAll(any());
    }

    private QuestionOption option(UUID id, Question question, String content, boolean correct, int orderIndex) {
        QuestionOption option = new QuestionOption();
        option.setId(id);
        option.setQuestion(question);
        option.setContent(content);
        option.setIsCorrect(correct);
        option.setOrderIndex(orderIndex);
        return option;
    }
}
