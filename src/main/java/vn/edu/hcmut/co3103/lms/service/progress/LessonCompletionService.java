package vn.edu.hcmut.co3103.lms.service.progress;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.response.CompletionMarkResponse;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.CompletionMark;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.CompletionMarkRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;

import java.time.Instant;
import java.util.UUID;

@Service
public class LessonCompletionService {
    private final ClassRepository classRepository;
    private final ClassMemberRepository classMemberRepository;
    private final ChapterRepository chapterRepository;
    private final LessonRepository lessonRepository;
    private final CompletionMarkRepository completionMarkRepository;

    public LessonCompletionService(
            ClassRepository classRepository,
            ClassMemberRepository classMemberRepository,
            ChapterRepository chapterRepository,
            LessonRepository lessonRepository,
            CompletionMarkRepository completionMarkRepository
    ) {
        this.classRepository = classRepository;
        this.classMemberRepository = classMemberRepository;
        this.chapterRepository = chapterRepository;
        this.lessonRepository = lessonRepository;
        this.completionMarkRepository = completionMarkRepository;
    }

    @Transactional
    public CompletionMarkResponse markComplete(UUID classId, UUID lessonId, UUID studentId) {
        ClassMember membership = requireWritableMembership(classId, studentId);
        requireAccessibleLesson(classId, lessonId);

        return completionMarkRepository
                .findByClassMemberIdAndLessonId(membership.getClassMemberId(), lessonId)
                .map(CompletionMarkResponse::from)
                .orElseGet(() -> {
                    CompletionMark created = new CompletionMark(
                            membership.getClassMemberId(),
                            lessonId,
                            Instant.now()
                    );
                    return CompletionMarkResponse.from(completionMarkRepository.save(created));
                });
    }

    @Transactional
    public CompletionMarkResponse markIncomplete(UUID classId, UUID lessonId, UUID studentId) {
        ClassMember membership = requireWritableMembership(classId, studentId);
        requireAccessibleLesson(classId, lessonId);
        completionMarkRepository.findByClassMemberIdAndLessonId(membership.getClassMemberId(), lessonId)
                .ifPresent(completionMarkRepository::delete);
        return CompletionMarkResponse.notCompleted(lessonId);
    }

    private ClassMember requireWritableMembership(UUID classId, UUID studentId) {
        Class targetClass = classRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> notFound("CLASS_NOT_FOUND"));
        if (targetClass.getStatus() != Class.ClassStatus.ACTIVE) {
            throw conflict("CLASS_ARCHIVED");
        }

        ClassMember membership = classMemberRepository.findLockedByClassIdAndStudentId(classId, studentId)
                .filter(member -> member.getStatus() == ClassMember.MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "MEMBERSHIP_REQUIRED"));
        return membership;
    }

    private void requireAccessibleLesson(UUID classId, UUID lessonId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> notFound("LESSON_NOT_FOUND"));
        Chapter chapter = chapterRepository.findById(lesson.getChapterId())
                .orElseThrow(() -> notFound("LESSON_NOT_FOUND"));
        if (!classId.equals(chapter.getClassId())) {
            throw notFound("LESSON_NOT_FOUND");
        }
        if (chapter.getStatus() != Chapter.VisibilityStatus.PUBLISHED
                || lesson.getStatus() != Chapter.VisibilityStatus.PUBLISHED) {
            throw conflict("LESSON_NOT_ACCESSIBLE");
        }
    }

    private ResponseStatusException notFound(String code) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, code);
    }

    private ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
}
