package vn.edu.hcmut.co3103.lms.service.adaptive.integration;

import org.springframework.stereotype.Component;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.entity.Question;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuestionRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * LMS-4/LMS-5 hierarchy adapter resolving Lesson, Activity, Quiz, and Question scopes
 * to their owning Class and current Quiz question set.
 */
@Component
public class LearningResourceScopeAdapter implements LearningResourceScope {

    private final LessonRepository lessonRepository;
    private final ChapterRepository chapterRepository;
    private final ActivityRepository activityRepository;
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;

    public LearningResourceScopeAdapter(LessonRepository lessonRepository,
                                        ChapterRepository chapterRepository,
                                        ActivityRepository activityRepository,
                                        QuizRepository quizRepository,
                                        QuestionRepository questionRepository) {
        this.lessonRepository = lessonRepository;
        this.chapterRepository = chapterRepository;
        this.activityRepository = activityRepository;
        this.quizRepository = quizRepository;
        this.questionRepository = questionRepository;
    }

    @Override
    public UUID classIdForLesson(UUID lessonId) {
        if (lessonId == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new AppException(ErrorCode.LEARNING_RESOURCE_NOT_FOUND));
        Chapter chapter = chapterRepository.findById(lesson.getChapterId())
                .orElseThrow(() -> new AppException(ErrorCode.LEARNING_RESOURCE_NOT_FOUND));
        if (chapter.getClassId() == null) {
            throw new AppException(ErrorCode.LEARNING_RESOURCE_NOT_FOUND);
        }
        return chapter.getClassId();
    }

    @Override
    public ActivityScope activityScope(UUID activityId) {
        if (activityId == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Activity activity = activityRepository.findById(activityId)
                .orElseThrow(() -> new AppException(ErrorCode.LEARNING_RESOURCE_NOT_FOUND));
        UUID classId = classIdForLesson(activity.getLessonId());
        ActivityKind kind = switch (activity.getActivityType()) {
            case QUIZ -> ActivityKind.QUIZ;
            case ASSIGNMENT -> ActivityKind.ASSIGNMENT;
        };
        return new ActivityScope(classId, kind);
    }

    @Override
    public QuestionScope questionScope(UUID questionId) {
        if (questionId == null) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new AppException(ErrorCode.LEARNING_RESOURCE_NOT_FOUND));
        Quiz quiz = question.getQuiz();
        if (quiz == null || quiz.getActivity() == null || quiz.getActivity().getActivityId() == null) {
            throw new AppException(ErrorCode.LEARNING_RESOURCE_NOT_FOUND);
        }
        UUID activityId = quiz.getActivity().getActivityId();
        ActivityScope activityScope = activityScope(activityId);
        return new QuestionScope(activityScope.classId(), activityId);
    }

    @Override
    public Set<UUID> questionIdsForQuizActivity(UUID activityId) {
        ActivityScope scope = activityScope(activityId);
        if (scope.kind() != ActivityKind.QUIZ) {
            throw new AppException(ErrorCode.INVALID_TOPIC_ASSOCIATION);
        }
        Optional<Quiz> quiz = quizRepository.findByActivity_ActivityId(activityId);
        if (quiz.isEmpty()) {
            return Set.of();
        }
        return questionRepository.findByQuiz_IdOrderByOrderIndex(quiz.get().getId()).stream()
                .map(Question::getId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
