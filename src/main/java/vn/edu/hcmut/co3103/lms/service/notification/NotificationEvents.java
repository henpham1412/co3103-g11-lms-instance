package vn.edu.hcmut.co3103.lms.service.notification;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.repository.notification.NotificationDeliveryRepository;
import vn.edu.hcmut.co3103.lms.repository.notification.NotificationDeliveryRepository.ContentScope;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class NotificationEvents {
    private final NotificationDeliveryRepository repository;
    private final EntityManager entities;
    private final Clock clock;

    public NotificationEvents(NotificationDeliveryRepository repository, EntityManager entities, Clock clock) {
        this.repository = repository;
        this.entities = entities;
        this.clock = clock;
    }

    public enum MembershipDecision {
        APPROVED("Your class request was approved", "You can now access this class."),
        REJECTED("Your class request was declined", "The tutor declined your request to join this class."),
        REMOVED("You were removed from a class", "The tutor removed your membership in this class.");

        private final String title;
        private final String message;

        MembershipDecision(String title, String message) {
            this.title = title;
            this.message = message;
        }
    }

    public void membershipDecided(ClassMember member, MembershipDecision decision) {
        var originalTransition = decision == MembershipDecision.REMOVED ? member.getJoinedAt() : member.getRequestedAt();
        String source = member.getClassMemberId() + ":" + decision + ":" + originalTransition;
        repository.send(member.getStudentId(), "ENROLLMENT_UPDATE", decision.title, decision.message,
                "/classes/" + member.getClassId(), source, now());
    }

    public void contentReleased(ContentScope scope, UUID id) {
        // The recipient query must see the source changes staged by JPA in this transaction.
        entities.flush();
        repository.notifyContent(scope, id, UUID.randomUUID().toString(), now());
    }

    public void assignmentSubmitted(UUID classId, UUID activityId, UUID submissionId, long version) {
        repository.notifySubmission(classId, activityId, submissionId + ":" + version, now());
    }

    public void assignmentGraded(UUID studentId, UUID submissionId, long version) {
        repository.send(studentId, "ASSESSMENT_RESULT", "Your assignment was graded",
                "Your tutor has provided a score or feedback.", "/assessment-history",
                "assignment:" + submissionId + ":" + version, now());
    }

    public void quizGraded(UUID studentId, UUID attemptId, OffsetDateTime gradedAt, boolean firstGrade) {
        String revision = firstGrade ? "initial" : gradedAt.toInstant().toString();
        repository.send(studentId, "ASSESSMENT_RESULT", "Your quiz was graded",
                "Your quiz result is available.", "/assessment-history",
                "quiz:" + attemptId + ":" + revision, now());
    }

    public void discussionReplied(UUID replyId) {
        repository.notifyReply(replyId, now());
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }
}
