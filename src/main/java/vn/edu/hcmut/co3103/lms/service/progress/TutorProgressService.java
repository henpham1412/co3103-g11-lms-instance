package vn.edu.hcmut.co3103.lms.service.progress;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.dto.response.AssessmentHistoryItem;
import vn.edu.hcmut.co3103.lms.dto.response.ClassProgressResponse;
import vn.edu.hcmut.co3103.lms.dto.response.TutorClassProgressResponse;
import vn.edu.hcmut.co3103.lms.dto.response.TutorMemberProgressResponse;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.CompletionMark;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.CompletionMarkRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TutorProgressService {
    private final ClassRepository classRepository;
    private final ClassMemberRepository classMemberRepository;
    private final LmsUserAccessRepository userAccessRepository;
    private final ChapterRepository chapterRepository;
    private final LessonRepository lessonRepository;
    private final CompletionMarkRepository completionMarkRepository;
    private final QuizAttemptRepository quizAttemptRepository;

    public TutorProgressService(ClassRepository classRepository, ClassMemberRepository classMemberRepository,
            LmsUserAccessRepository userAccessRepository, ChapterRepository chapterRepository,
            LessonRepository lessonRepository, CompletionMarkRepository completionMarkRepository,
            QuizAttemptRepository quizAttemptRepository) {
        this.classRepository = classRepository;
        this.classMemberRepository = classMemberRepository;
        this.userAccessRepository = userAccessRepository;
        this.chapterRepository = chapterRepository;
        this.lessonRepository = lessonRepository;
        this.completionMarkRepository = completionMarkRepository;
        this.quizAttemptRepository = quizAttemptRepository;
    }

    @Transactional(readOnly = true)
    public TutorClassProgressResponse getClassProgress(UUID classId, UUID tutorId) {
        requireOwnedClass(classId, tutorId);
        List<TutorClassProgressResponse.MemberSummary> members = classMemberRepository
                .findByClassIdAndStatusOrderByJoinedAtAsc(classId, ClassMember.MembershipStatus.ACTIVE)
                .stream().map(member -> {
                    ProgressCounts counts = counts(member);
                    LmsUserAccess student = userAccessRepository.findById(member.getStudentId()).orElse(null);
                    return new TutorClassProgressResponse.MemberSummary(member.getClassMemberId(),
                            member.getStudentId(), student == null ? null : student.getFullName(),
                            counts.completed(), counts.eligible(), percentage(counts.completed(), counts.eligible()),
                            progressStatus(counts.completed(), counts.eligible()));
                }).toList();
        return new TutorClassProgressResponse(classId, members);
    }

    @Transactional(readOnly = true)
    public TutorMemberProgressResponse getMemberProgress(UUID classId, UUID classMemberId, UUID tutorId) {
        requireOwnedClass(classId, tutorId);
        ClassMember member = classMemberRepository.findByClassMemberIdAndClassId(classMemberId, classId)
                .filter(candidate -> candidate.getStatus() == ClassMember.MembershipStatus.ACTIVE
                        || candidate.getStatus() == ClassMember.MembershipStatus.INACTIVE)
                .orElseThrow(() -> notFound("CLASS_OR_MEMBER_NOT_FOUND"));
        ProgressCounts counts = counts(member);
        var marksByLesson = completionMarkRepository.findByClassMemberId(member.getClassMemberId()).stream()
                .collect(Collectors.toMap(CompletionMark::getLessonId, mark -> mark, (left, right) -> left));
        List<ClassProgressResponse.ChapterProgress> chapters = new ArrayList<>();
        for (Chapter chapter : chapterRepository.findByClassIdAndStatusOrderBySequenceOrderAsc(
                classId, Chapter.VisibilityStatus.PUBLISHED)) {
            List<Lesson> lessons = lessonRepository.findByChapterIdAndStatusOrderBySequenceOrderAsc(
                    chapter.getChapterId(), Chapter.VisibilityStatus.PUBLISHED);
            List<ClassProgressResponse.LessonProgress> lessonItems = lessons.stream().map(lesson ->
                    new ClassProgressResponse.LessonProgress(lesson.getLessonId(), lesson.getTitle(),
                            lesson.getSequenceOrder(), marksByLesson.containsKey(lesson.getLessonId()),
                            marksByLesson.containsKey(lesson.getLessonId())
                                    ? marksByLesson.get(lesson.getLessonId()).getMarkedAt() : null, List.of()))
                    .toList();
            int completed = (int) lessons.stream().filter(lesson -> marksByLesson.containsKey(lesson.getLessonId())).count();
            chapters.add(new ClassProgressResponse.ChapterProgress(chapter.getChapterId(), chapter.getTitle(),
                    completed, lessons.size(), percentage(completed, lessons.size()), lessonItems));
        }
        List<AssessmentHistoryItem> assessments = quizAttemptRepository
                .findByClassMember_ClassMemberId(member.getClassMemberId()).stream()
                .map(AssessmentHistoryItem::from).toList();
        return new TutorMemberProgressResponse(classId, member.getClassMemberId(), member.getStudentId(),
                member.getStatus().name(), counts.completed(), counts.eligible(),
                percentage(counts.completed(), counts.eligible()), progressStatus(counts.completed(), counts.eligible()),
                chapters, assessments, List.of());
    }

    private ProgressCounts counts(ClassMember member) {
        List<Lesson> lessons = lessonRepository.findEligibleLessons(member.getClassId(), Chapter.VisibilityStatus.PUBLISHED);
        Set<UUID> eligibleIds = lessons.stream().map(Lesson::getLessonId).collect(Collectors.toSet());
        int completed = (int) completionMarkRepository.findByClassMemberId(member.getClassMemberId()).stream()
                .map(CompletionMark::getLessonId).filter(eligibleIds::contains).distinct().count();
        return new ProgressCounts(completed, lessons.size());
    }

    private void requireOwnedClass(UUID classId, UUID tutorId) {
        Class targetClass = classRepository.findById(classId)
                .orElseThrow(() -> notFound("CLASS_OR_MEMBER_NOT_FOUND"));
        if (!targetClass.getTutorId().equals(tutorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    private int percentage(int completed, int eligible) {
        return eligible == 0 ? 100 : (int) Math.round(completed * 100.0 / eligible);
    }

    private String progressStatus(int completed, int eligible) {
        return eligible > 0 && completed == eligible ? "COMPLETED" : "IN_PROGRESS";
    }

    private ResponseStatusException notFound(String code) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, code);
    }

    private record ProgressCounts(int completed, int eligible) { }
}
