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
import vn.edu.hcmut.co3103.lms.entity.LastVisit;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.entity.Quiz;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.CompletionMarkRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LastVisitRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
        ClassMember membership = requireActiveStudentMember(classId, studentId);

        OffsetDateTime now = OffsetDateTime.now();
        Map<UUID, CompletionMark> marksByLesson = completionMarkRepository
                .findByClassMemberId(membership.getClassMemberId()).stream()
                .collect(Collectors.toMap(CompletionMark::getLessonId, mark -> mark, (left, right) -> left));
        List<QuizAttempt> memberAttempts = quizAttemptRepository
                .findByClassMember_ClassMemberId(membership.getClassMemberId());
        Map<UUID, List<QuizAttempt>> attemptsByActivity = groupAttemptsByActivity(memberAttempts);

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
                        buildAssessmentStatuses(lesson.getLessonId(), attemptsByActivity, now)
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
        ClassProgressResponse.ResumeTarget resumeTarget = resolveResumeTarget(
                targetClass, membership, memberAttempts, marksByLesson.keySet(), firstIncompleteLessonId, now
        );
        return new ClassProgressResponse(
                targetClass.getClassId(), totalCompleted, totalEligible, percentage(totalCompleted, totalEligible),
                status, chapterItems, resumeTarget
        );
    }

    @Transactional(readOnly = true)
    public ClassProgressResponse.ResumeTarget getResumeTarget(UUID classId, UUID studentId) {
        Class targetClass = classRepository.findById(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CLASS_NOT_FOUND"));
        ClassMember member = requireActiveStudentMember(classId, studentId);

        OffsetDateTime now = OffsetDateTime.now();
        List<QuizAttempt> memberAttempts = targetClass.getStatus() == Class.ClassStatus.ACTIVE
                ? quizAttemptRepository.findByClassMember_ClassMemberId(member.getClassMemberId())
                : List.of();
        Set<UUID> completedLessonIds = completionMarkRepository.findByClassMemberId(member.getClassMemberId())
                .stream().map(CompletionMark::getLessonId).collect(Collectors.toSet());
        UUID firstIncomplete = lessonRepository.findEligibleLessons(classId, Chapter.VisibilityStatus.PUBLISHED)
                .stream().map(Lesson::getLessonId).filter(id -> !completedLessonIds.contains(id))
                .findFirst().orElse(null);

        return resolveResumeTarget(targetClass, member, memberAttempts, completedLessonIds, firstIncomplete, now);
    }

    private ClassMember requireActiveStudentMember(UUID classId, UUID studentId) {
        return classMemberRepository.findByClassIdAndStudentId(classId, studentId)
                .filter(member -> member.getStatus() == ClassMember.MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "MEMBERSHIP_REQUIRED"));
    }

    private ClassProgressResponse.ResumeTarget resolveResumeTarget(
            Class targetClass,
            ClassMember member,
            List<QuizAttempt> memberAttempts,
            Set<UUID> completedLessonIds,
            UUID firstIncompleteLessonId,
            OffsetDateTime now
    ) {
        UUID classId = targetClass.getClassId();
        if (targetClass.getStatus() == Class.ClassStatus.ACTIVE
                && member.getStatus() == ClassMember.MembershipStatus.ACTIVE) {
            QuizAttempt inProgress = memberAttempts.stream()
                    .filter(attempt -> "IN_PROGRESS".equals(attempt.getStatus()))
                    .filter(attempt -> isAvailableQuizAttempt(attempt, classId, now))
                    .max(Comparator.comparing(QuizAttempt::getStartedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(QuizAttempt::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .orElse(null);
            if (inProgress != null) {
                return new ClassProgressResponse.ResumeTarget(
                        "QUIZ_ATTEMPT", inProgress.getId(), "IN_PROGRESS_ATTEMPT");
            }
        }

        LastVisit lastVisit = lastVisitRepository.findById(member.getClassMemberId()).orElse(null);
        if (lastVisit != null
                && !completedLessonIds.contains(lastVisit.getLessonId())
                && isVisibleLessonInClass(lastVisit.getLessonId(), classId)) {
            return new ClassProgressResponse.ResumeTarget(
                    "LESSON", lastVisit.getLessonId(), "MOST_RECENTLY_VISITED_LESSON");
        }

        if (firstIncompleteLessonId != null) {
            return new ClassProgressResponse.ResumeTarget(
                    "LESSON", firstIncompleteLessonId, "FIRST_INCOMPLETE_LESSON");
        }
        return new ClassProgressResponse.ResumeTarget("CLASS_OUTLINE", classId, "NO_UNFINISHED_TARGET");
    }

    private boolean isAvailableQuizAttempt(QuizAttempt attempt, UUID classId, OffsetDateTime now) {
        Quiz quiz = attempt.getQuiz();
        if (quiz == null) return false;
        Activity activity = quiz.getActivity();
        if (activity == null
                || activity.getActivityType() != Activity.ActivityType.QUIZ
                || activity.getStatus() != Chapter.VisibilityStatus.PUBLISHED
                || !isVisibleLessonInClass(activity.getLessonId(), classId)) {
            return false;
        }
        return isUnexpiredAttemptWindow(attempt, quiz, now);
    }

    static boolean isUnexpiredAttemptWindow(QuizAttempt attempt, Quiz quiz, OffsetDateTime now) {
        if (!"IN_PROGRESS".equals(attempt.getStatus())) {
            return false;
        }
        if (quiz == null || attempt.getStartedAt() == null
                || quiz.getTimeLimitMinutes() == null || quiz.getDeadline() == null) {
            return true;
        }
        OffsetDateTime endAt = attempt.getStartedAt().plusMinutes(quiz.getTimeLimitMinutes());
        if (quiz.getDeadline().isBefore(endAt)) {
            endAt = quiz.getDeadline();
        }
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

    static Map<UUID, List<QuizAttempt>> groupAttemptsByActivity(List<QuizAttempt> attempts) {
        Map<UUID, List<QuizAttempt>> byActivity = new HashMap<>();
        for (QuizAttempt attempt : attempts) {
            if (attempt.getQuiz() == null || attempt.getQuiz().getActivity() == null) continue;
            Activity activity = attempt.getQuiz().getActivity();
            byActivity.computeIfAbsent(activity.getActivityId(), ignored -> new ArrayList<>()).add(attempt);
        }
        return byActivity;
    }

    private List<ClassProgressResponse.AssessmentStatus> buildAssessmentStatuses(
            UUID lessonId,
            Map<UUID, List<QuizAttempt>> attemptsByActivity,
            OffsetDateTime now
    ) {
        return buildLessonAssessmentStatuses(activityRepository, lessonId, attemptsByActivity, now);
    }

    static List<ClassProgressResponse.AssessmentStatus> buildLessonAssessmentStatuses(
            ActivityRepository activityRepository,
            UUID lessonId,
            Map<UUID, List<QuizAttempt>> attemptsByActivity,
            OffsetDateTime now
    ) {
        List<ClassProgressResponse.AssessmentStatus> statuses = new ArrayList<>();
        List<Activity> activities = activityRepository == null
                ? List.of()
                : activityRepository.findByLessonIdAndStatusOrderBySequenceOrderAsc(
                        lessonId, Chapter.VisibilityStatus.PUBLISHED
                );
        for (Activity activity : activities) {
            if (activity.getActivityType() != Activity.ActivityType.QUIZ) continue;
            List<QuizAttempt> attempts = attemptsByActivity.getOrDefault(activity.getActivityId(), List.of());
            List<ClassProgressResponse.AttemptStatus> attemptItems = attempts.stream()
                    .sorted(Comparator.comparing(QuizAttempt::getAttemptNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(QuizAttempt::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(attempt -> new ClassProgressResponse.AttemptStatus(
                            attempt.getId(), attempt.getAttemptNumber(), attempt.getStatus(), attempt.getStartedAt(),
                            attempt.getSubmittedAt(), attempt.getScore()
                    )).toList();
            boolean hasActiveAttempt = attempts.stream()
                    .anyMatch(attempt -> isUnexpiredAttemptWindow(attempt, attempt.getQuiz(), now));
            String status = hasActiveAttempt
                    ? "IN_PROGRESS"
                    : attempts.isEmpty() ? "NOT_STARTED" : "SUBMITTED";
            statuses.add(new ClassProgressResponse.AssessmentStatus(
                    activity.getActivityId(), activity.getTitle(), activity.getActivityType().name(), status, attemptItems
            ));
        }
        return statuses;
    }

    private int percentage(int completed, int eligible) {
        return eligible == 0 ? 0 : (int) Math.round(completed * 100.0 / eligible);
    }
}
