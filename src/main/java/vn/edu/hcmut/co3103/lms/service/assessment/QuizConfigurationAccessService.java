package vn.edu.hcmut.co3103.lms.service.assessment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.entity.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.service.classmanagement.ClassMembershipService;

import java.util.UUID;

@Service
public class QuizConfigurationAccessService {
    private final LmsUserAccessRepository userAccessRepository;
    private final ClassMembershipService membershipService;

    public QuizConfigurationAccessService(
            LmsUserAccessRepository userAccessRepository,
            ClassMembershipService membershipService
    ) {
        this.userAccessRepository = userAccessRepository;
        this.membershipService = membershipService;
    }

    public void requireCanView(Activity activity, UUID userId) {
        LmsUserAccess user = userAccessRepository.findById(userId)
                .filter(access -> "ACTIVE".equals(access.getAccessStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN"));

        Lesson lesson = activity.getLesson();
        Chapter chapter = lesson.getChapter();
        Class targetClass = chapter.getClassField();

        if ("TUTOR".equals(user.getLmsRole())) {
            if (!targetClass.getTutorId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
            }
            return;
        }

        if (!"STUDENT".equals(user.getLmsRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        membershipService.requireActiveMembership(targetClass.getClassId(), userId);
        if (targetClass.getStatus() != Class.ClassStatus.ACTIVE
                || !"PUBLISHED".equals(chapter.getStatus())
                || !"PUBLISHED".equals(lesson.getStatus())
                || !"PUBLISHED".equals(activity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND");
        }
    }
}
