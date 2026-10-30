package vn.edu.hcmut.co3103.lms.service.assessment;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.QuestionCreateRequest;
import vn.edu.hcmut.co3103.lms.dto.response.QuestionResponse;
import vn.edu.hcmut.co3103.lms.entity.Question;
import vn.edu.hcmut.co3103.lms.entity.QuestionOption;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionOptionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuizQuestionService {
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final QuestionOptionRepository optionRepository;
    private final QuizConfigurationAccessService accessService;

    @Transactional
    public QuestionResponse addQuestion(UUID activityId, UUID userId, QuestionCreateRequest request) {
        Quiz quiz = quizRepository.findByActivity_Id(activityId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND"));
        accessService.requireOwningTutor(quiz.getActivity(), userId);
        validateQuestion(request);

        Question question = new Question();
        question.setQuiz(quiz);
        question.setQuestionType(request.questionType());
        question.setContent(request.content().trim());
        question.setPointsAwarded(request.pointsAwarded());
        question.setPointsDeducted(request.pointsDeducted());
        question.setOrderIndex(request.orderIndex());
        Question savedQuestion = questionRepository.save(question);

        List<QuestionResponse.Option> options = request.options().stream().map(option -> {
            QuestionOption entity = new QuestionOption();
            entity.setQuestion(savedQuestion);
            entity.setContent(option.content().trim());
            entity.setIsCorrect(option.isCorrect());
            entity.setOrderIndex(option.orderIndex());
            QuestionOption saved = optionRepository.save(entity);
            return new QuestionResponse.Option(saved.getId(), saved.getContent(),
                    saved.getIsCorrect(), saved.getOrderIndex());
        }).toList();

        return new QuestionResponse(savedQuestion.getId(), savedQuestion.getQuestionType(),
                savedQuestion.getContent(), savedQuestion.getPointsAwarded(),
                savedQuestion.getPointsDeducted(), savedQuestion.getOrderIndex(), options);
    }

    private void validateQuestion(QuestionCreateRequest request) {
        if (request == null || request.questionType() == null
                || !Set.of("MCQ_SINGLE", "MCQ_MULTIPLE", "TRUE_FALSE").contains(request.questionType())
                || request.content() == null || request.content().isBlank()
                || request.pointsAwarded() == null || request.pointsAwarded().compareTo(BigDecimal.ZERO) < 0
                || request.pointsDeducted() == null || request.pointsDeducted().compareTo(BigDecimal.ZERO) < 0
                || request.orderIndex() == null || request.orderIndex() < 0
                || request.options() == null || request.options().isEmpty()
                || (request.questionType().startsWith("MCQ_") && request.options().size() < 2)
                || (request.skillTopicIds() != null && !request.skillTopicIds().isEmpty())) {
            /* TODO: validate skillTopicIds with the actual skill topics of the class;
            for now, we just check that the list is not empty if provided */
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION");
        }

        long correctCount = 0;
        Set<Integer> optionOrders = new HashSet<>();
        for (QuestionCreateRequest.Option option : request.options()) {
            if (option == null || option.content() == null || option.content().isBlank()
                    || option.isCorrect() == null || option.orderIndex() == null || option.orderIndex() < 0
                    || !optionOrders.add(option.orderIndex())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION");
            }
            if (option.isCorrect()) correctCount++;
        }
        if (correctCount == 0 || (("MCQ_SINGLE".equals(request.questionType())
                || "TRUE_FALSE".equals(request.questionType())) && correctCount != 1)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUESTION");
        }
    }
}
