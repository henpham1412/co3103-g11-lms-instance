package vn.edu.hcmut.co3103.lms.service.progress;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.response.LastVisitResponse;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.LastVisit;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LastVisitRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;

import java.time.Instant;
import java.util.UUID;

@Service
public class LastVisitService {
    private final ClassRepository classRepository;
    private final ClassMemberRepository classMemberRepository;
    private final ChapterRepository chapterRepository;
    private final LessonRepository lessonRepository;
    private final LastVisitRepository lastVisitRepository;

    public LastVisitService(
            ClassRepository classRepository,
            ClassMemberRepository classMemberRepository,
            ChapterRepository chapterRepository,
            LessonRepository lessonRepository,
            LastVisitRepository lastVisitRepository
    ) {
        this.classRepository = classRepository;
        this.classMemberRepository = classMemberRepository;
        this.chapterRepository = chapterRepository;
        this.lessonRepository = lessonRepository;
        this.lastVisitRepository = lastVisitRepository;
    }

    @Transactional
    public LastVisitResponse recordVisit(UUID classId, UUID lessonId, UUID studentId) {
        Class targetClass = classRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> notFound("CLASS_NOT_FOUND"));
        if (targetClass.getStatus() != Class.ClassStatus.ACTIVE) {
            throw conflict("CLASS_ARCHIVED");
        }

        ClassMember membership = classMemberRepository.findLockedByClassIdAndStudentId(classId, studentId)
                .filter(member -> member.getStatus() == ClassMember.MembershipStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "MEMBERSHIP_REQUIRED"));
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

        Instant visitedAt = Instant.now();
        LastVisit lastVisit = lastVisitRepository.findById(membership.getClassMemberId())
                .orElseGet(() -> new LastVisit(membership.getClassMemberId(), lessonId, visitedAt));
        lastVisit.setLessonId(lessonId);
        lastVisit.setVisitedAt(visitedAt);
        return LastVisitResponse.from(lastVisitRepository.save(lastVisit));
    }

    private ResponseStatusException notFound(String code) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, code);
    }

    private ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
}
