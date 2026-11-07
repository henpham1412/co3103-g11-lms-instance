package vn.edu.hcmut.co3103.lms.service.progress;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.response.ClassProgressResponse;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.CompletionMark;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.entity.LastVisit;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.CompletionMarkRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LastVisitRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.util.stream.Collectors;

@Service
public class StudentProgressService {
    private final ClassRepository classRepository;
    private final ClassMemberRepository classMemberRepository;
    private final ChapterRepository chapterRepository;
    private final LessonRepository lessonRepository;
    private final ActivityRepository activityRepository;
    private final CompletionMarkRepository completionMarkRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final LastVisitRepository lastVisitRepository;

    public StudentProgressService(
            ClassRepository classRepository,
            ClassMemberRepository classMemberRepository,
            ChapterRepository chapterRepository,
            LessonRepository lessonRepository,
            ActivityRepository activityRepository,
            CompletionMarkRepository completionMarkRepository,
            QuizAttemptRepository quizAttemptRepository,
            LastVisitRepository lastVisitRepository
    ) {
        this.classRepository = classRepository;
        this.classMemberRepository = classMemberRepository;
        this.chapterRepository = chapterRepository;
        this.lessonRepository = lessonRepository;
        this.activityRepository = activityRepository;
        this.completionMarkRepository = completionMarkRepository;
        this.quizAttemptRepository = quizAttemptRepository;
        this.lastVisitRepository = lastVisitRepository;
    }

    @Transactional(readOnly = true)
    public ClassProgressResponse getClassProgress(UUID classId, UUID studentId) {
        Class targetClass = classRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CLASS_NOT_FOUND"));
        ClassMember membership = classMemberRepository.findByClassIdAndStudentId(classId, studentId)
                .filter(member -> member.getStatus() == ClassMember.MembershipStatus.ACTIVE
                        || member.getStatus() == ClassMember.MembershipStatus.INACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "MEMBERSHIP_REQUIRED"));

        Map<UUID, CompletionMark> marksByLesson = completionMarkRepository
                .findByClassMemberId(membership.getClassMemberId()).stream()
                .collect(Collectors.toMap(CompletionMark::getLessonId, mark -> mark, (left, right) -> left));
        Map<UUID, List<QuizAttempt>> attemptsByActivity = loadAttemptsByActivity(membership.getClassMemberId());

        List<ClassProgressResponse.ChapterProgress> chapterItems = new ArrayList<>();
        int totalEligible = 0;
        int totalCompleted = 0;
        UUID firstIncompleteLessonId = null;

        List<Chapter> chapters = chapterRepository.findByClassIdAndStatusOrderBySequenceOrderAsc(
                targetClass.getClassId(), Chapter.VisibilityStatus.PUBLISHED
        );
        for (Chapter chapter : chapters) {
            List<Lesson> lessons = lessonRepository.findByChapterIdAndStatusOrderBySequenceOrderAsc(
                    chapter.getChapterId(), Chapter.VisibilityStatus.PUBLISHED
            );
            List<ClassProgressResponse.LessonProgress> lessonItems = new ArrayList<>();
            int chapterCompleted = 0;
            for (Lesson lesson : lessons) {
                CompletionMark mark = marksByLesson.get(lesson.getLessonId());
                boolean completed = mark != null;
                if (!completed && firstIncompleteLessonId == null) {
                    firstIncompleteLessonId = lesson.getLessonId();
                }
                if (completed) chapterCompleted++;
                lessonItems.add(new ClassProgressResponse.LessonProgress(
                        lesson.getLessonId(), lesson.getTitle(), lesson.getSequenceOrder(), completed,
                        mark == null ? null : mark.getMarkedAt(),
                        buildAssessmentStatuses(lesson.getLessonId(), attemptsByActivity)
                ));
            }
            totalEligible += lessons.size();
            totalCompleted += chapterCompleted;
            chapterItems.add(new ClassProgressResponse.ChapterProgress(
                    chapter.getChapterId(), chapter.getTitle(), chapterCompleted, lessons.size(),
                    percentage(chapterCompleted, lessons.size()), lessonItems
            ));
        }

        String status = totalEligible > 0 && totalCompleted == totalEligible ? "COMPLETED" : "IN_PROGRESS";
        ClassProgressResponse.ResumeTarget resumeTarget = firstIncompleteLessonId == null
                ? new ClassProgressResponse.ResumeTarget("CLASS_OUTLINE", targetClass.getClassId(), "NO_UNFINISHED_TARGET")
                : new ClassProgressResponse.ResumeTarget("LESSON", firstIncompleteLessonId, "FIRST_INCOMPLETE_LESSON");
        return new ClassProgressResponse(
                targetClass.getClassId(), totalCompleted, totalEligible, percentage(totalCompleted, totalEligible),
                status, chapterItems, resumeTarget
        );
    }

    @Transactional(readOnly = true)
    public ClassProgressResponse.ResumeTarget getResumeTarget(UUID classId, UUID studentId) {
        Class targetClass = classRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CLASS_NOT_FOUND"));
        ClassMember member = classMemberRepository.findByClassIdAndStudentId(classId, studentId)
                .filter(candidate -> candidate.getStatus() == ClassMember.MembershipStatus.ACTIVE
                        || candidate.getStatus() == ClassMember.MembershipStatus.INACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "MEMBERSHIP_REQUIRED"));

        if (targetClass.getStatus() == Class.ClassStatus.ACTIVE
                && member.getStatus() == ClassMember.MembershipStatus.ACTIVE) {
            OffsetDateTime now = OffsetDateTime.now();
            QuizAttempt inProgress = quizAttemptRepository.findByClassMember_ClassMemberId(member.getClassMemberId())
                    .stream()
                    .filter(attempt -> "IN_PROGRESS".equals(attempt.getStatus()))
                    .filter(attempt -> isAvailableQuizAttempt(attempt, classId, now))
                    .max(java.util.Comparator.comparing(QuizAttempt::getStartedAt)
                            .thenComparing(QuizAttempt::getId))
                    .orElse(null);
            if (inProgress != null) {
                return new ClassProgressResponse.ResumeTarget(
                        "QUIZ_ATTEMPT", inProgress.getId(), "IN_PROGRESS_ATTEMPT");
            }
        }

        LastVisit lastVisit = lastVisitRepository.findById(member.getClassMemberId()).orElse(null);
        if (lastVisit != null && isVisibleLessonInClass(lastVisit.getLessonId(), classId)) {
            return new ClassProgressResponse.ResumeTarget(
                    "LESSON", lastVisit.getLessonId(), "MOST_RECENTLY_VISITED_LESSON");
        }

        Set<UUID> completedLessonIds = completionMarkRepository.findByClassMemberId(member.getClassMemberId())
                .stream().map(CompletionMark::getLessonId).collect(Collectors.toSet());
        UUID firstIncomplete = lessonRepository.findEligibleLessons(classId, Chapter.VisibilityStatus.PUBLISHED)
                .stream().map(Lesson::getLessonId).filter(id -> !completedLessonIds.contains(id))
                .findFirst().orElse(null);
        if (firstIncomplete != null) {
            return new ClassProgressResponse.ResumeTarget("LESSON", firstIncomplete, "FIRST_INCOMPLETE_LESSON");
        }
        return new ClassProgressResponse.ResumeTarget("CLASS_OUTLINE", classId, "NO_UNFINISHED_TARGET");
    }

    private boolean isAvailableQuizAttempt(QuizAttempt attempt, UUID classId, OffsetDateTime now) {
        Quiz quiz = attempt.getQuiz();
        Activity activity = quiz.getActivity();
        if (activity.getActivityType() != Activity.ActivityType.QUIZ
                || activity.getStatus() != Chapter.VisibilityStatus.PUBLISHED
                || !isVisibleLessonInClass(activity.getLessonId(), classId)) {
            return false;
        }
        OffsetDateTime endAt = attempt.getStartedAt().plusMinutes(quiz.getTimeLimitMinutes());
        if (quiz.getDeadline().isBefore(endAt)) endAt = quiz.getDeadline();
        return now.isBefore(endAt);
    }

    private boolean isVisibleLessonInClass(UUID lessonId, UUID classId) {
        if (lessonId == null || classId == null) return false;
        Lesson lesson = lessonRepository.findById(lessonId).orElse(null);
        if (lesson == null || lesson.getStatus() != Chapter.VisibilityStatus.PUBLISHED) return false;
        Chapter chapter = chapterRepository.findById(lesson.getChapterId()).orElse(null);
        return chapter != null && chapter.getClassId().equals(classId)
                && chapter.getStatus() == Chapter.VisibilityStatus.PUBLISHED;
    }

    private Map<UUID, List<QuizAttempt>> loadAttemptsByActivity(UUID classMemberId) {
        Map<UUID, List<QuizAttempt>> byActivity = new HashMap<>();
        for (QuizAttempt attempt : quizAttemptRepository.findByClassMember_ClassMemberId(classMemberId)) {
            Activity activity = attempt.getQuiz().getActivity();
            byActivity.computeIfAbsent(activity.getActivityId(), ignored -> new ArrayList<>()).add(attempt);
        }
        return byActivity;
    }

    private List<ClassProgressResponse.AssessmentStatus> buildAssessmentStatuses(
            UUID lessonId,
            Map<UUID, List<QuizAttempt>> attemptsByActivity
    ) {
        List<ClassProgressResponse.AssessmentStatus> statuses = new ArrayList<>();
        for (Activity activity : activityRepository.findByLessonIdAndStatusOrderBySequenceOrderAsc(
                lessonId, Chapter.VisibilityStatus.PUBLISHED
        )) {
            if (activity.getActivityType() != Activity.ActivityType.QUIZ) continue;
            List<QuizAttempt> attempts = attemptsByActivity.getOrDefault(activity.getActivityId(), List.of());
            List<ClassProgressResponse.AttemptStatus> attemptItems = attempts.stream()
                    .map(attempt -> new ClassProgressResponse.AttemptStatus(
                            attempt.getId(), attempt.getAttemptNumber(), attempt.getStatus(), attempt.getStartedAt(),
                            attempt.getSubmittedAt(), attempt.getScore()
                    )).toList();
            String status = attempts.stream().anyMatch(attempt -> "IN_PROGRESS".equals(attempt.getStatus()))
                    ? "IN_PROGRESS"
                    : attempts.isEmpty() ? "NOT_STARTED" : "SUBMITTED";
            statuses.add(new ClassProgressResponse.AssessmentStatus(
                    activity.getActivityId(), activity.getTitle(), activity.getActivityType().name(), status, attemptItems
            ));
        }
        return statuses;
    }

    private int percentage(int completed, int eligible) {
        return eligible == 0 ? 100 : (int) Math.round(completed * 100.0 / eligible);
    }
}
