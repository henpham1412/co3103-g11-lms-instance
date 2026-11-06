package vn.edu.hcmut.co3103.lms.service.assessment;

import org.springframework.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ChapterRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;
import vn.edu.hcmut.co3103.lms.service.classmanagement.ClassMembershipService;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuizConfigurationAccessService {
    private final LmsUserAccessRepository userAccessRepository;
    private final ClassMembershipService membershipService;
    private final ClassMemberRepository classMemberRepository;
    private final LessonRepository lessonRepository;
    private final ChapterRepository chapterRepository;
    private final ClassRepository classRepository;

    public void requireCanView(Activity activity, UUID userId) {
        LmsUserAccess user = requireActiveUser(userId);

        ContentPath contentPath = resolveContentPath(activity);
        Lesson lesson = contentPath.lesson();
        Chapter chapter = contentPath.chapter();
        Class targetClass = contentPath.targetClass();

        if (user.getLmsRole() == LmsRole.TUTOR) {
            if (!targetClass.getTutorId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
            }
            return;
        }

        if (user.getLmsRole() != LmsRole.STUDENT) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        membershipService.requireActiveMembership(targetClass.getClassId(), userId);
        // TODO: optimize this by delegating this to the actual responsible service
        if (targetClass.getStatus() != Class.ClassStatus.ACTIVE
                || chapter.getStatus() != Chapter.VisibilityStatus.PUBLISHED
                || lesson.getStatus() != Chapter.VisibilityStatus.PUBLISHED
                || activity.getStatus() != Chapter.VisibilityStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND");
        }
    }

    public void requireOwningTutor(Activity activity, UUID userId) {
        LmsUserAccess user = requireActiveUser(userId);
        Class targetClass = resolveContentPath(activity).targetClass();
        if (user.getLmsRole() != LmsRole.TUTOR || !targetClass.getTutorId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    public ClassMember requireEligibleStudent(Activity activity, UUID userId) {
        LmsUserAccess user = requireActiveUser(userId);
        if (user.getLmsRole() != LmsRole.STUDENT) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        ContentPath contentPath = resolveContentPath(activity);
        Lesson lesson = contentPath.lesson();
        Chapter chapter = contentPath.chapter();
        Class targetClass = contentPath.targetClass();
        membershipService.requireActiveMembership(targetClass.getClassId(), userId);
        ClassMember member = classMemberRepository.findByClassIdAndStudentId(targetClass.getClassId(), userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "MEMBERSHIP_REQUIRED"));
        if (targetClass.getStatus() != Class.ClassStatus.ACTIVE
                || chapter.getStatus() != Chapter.VisibilityStatus.PUBLISHED
                || lesson.getStatus() != Chapter.VisibilityStatus.PUBLISHED
                || activity.getStatus() != Chapter.VisibilityStatus.PUBLISHED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "QUIZ_NOT_AVAILABLE");
        }
        return member;
    }

    private LmsUserAccess requireActiveUser(UUID userId) {
        return userAccessRepository.findById(userId)
                .filter(access -> access.getAccessStatus() == AccessStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN"));
    }

    private ContentPath resolveContentPath(Activity activity) {
        if (activity == null || activity.getLessonId() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND");
        }

        Lesson lesson = lessonRepository.findById(activity.getLessonId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND"));
        Chapter chapter = chapterRepository.findById(lesson.getChapterId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND"));
        Class targetClass = classRepository.findById(chapter.getClassId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND"));
        return new ContentPath(lesson, chapter, targetClass);
    }

    private record ContentPath(Lesson lesson, Chapter chapter, Class targetClass) {
    }
}
