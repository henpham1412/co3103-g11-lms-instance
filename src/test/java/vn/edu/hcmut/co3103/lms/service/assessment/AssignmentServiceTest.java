package vn.edu.hcmut.co3103.lms.service.assessment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Assignment;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.Submission;
import vn.edu.hcmut.co3103.lms.repository.assessment.AssignmentAttachmentRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.AssignmentRepository;
import vn.edu.hcmut.co3103.lms.repository.assessment.SubmissionRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.ActivityRepository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssignmentServiceTest {
    private final AssignmentRepository assignments = mock(AssignmentRepository.class);
    private final AssignmentAttachmentRepository attachments = mock(AssignmentAttachmentRepository.class);
    private final SubmissionRepository submissions = mock(SubmissionRepository.class);
    private final ActivityRepository activities = mock(ActivityRepository.class);
    private final ClassRepository classes = mock(ClassRepository.class);
    private final ClassMemberRepository members = mock(ClassMemberRepository.class);
    private final AssignmentFileStorageService storage = mock(AssignmentFileStorageService.class);
    private AssignmentService service;

    private final UUID assignmentId = UUID.randomUUID();
    private final UUID activityId = UUID.randomUUID();
    private final UUID classId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AssignmentService(assignments, attachments, submissions, activities,
                classes, members, storage);
        Activity activity = new Activity(UUID.randomUUID(), "Assignment", null,
                Activity.ActivityType.ASSIGNMENT, 0, null);
        when(activities.findById(activityId)).thenReturn(Optional.of(activity));
        when(activities.findClassIdByActivityId(activityId)).thenReturn(Optional.of(classId));
        Assignment assignment = new Assignment(activityId, null, OffsetDateTime.now().plusHours(1),
                false, "text/plain", 5);
        assignment.setAssignmentId(assignmentId);
        when(assignments.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(members.findByClassIdAndStudentId(classId, studentId)).thenReturn(Optional.of(
                new ClassMember(classId, studentId, ClassMember.MembershipStatus.ACTIVE, null, null)));
    }

    @Test
    void rejectsSubmissionAfterDeadlineWhenLateSubmissionsDisabled() {
        Assignment assignment = new Assignment(activityId, null, OffsetDateTime.now().minusSeconds(1),
                false, "text/plain", 5);
        assignment.setAssignmentId(assignmentId);
        when(assignments.findById(assignmentId)).thenReturn(Optional.of(assignment));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.submit(assignmentId, studentId,
                        new MockMultipartFile("file", "work.txt", "text/plain", "x".getBytes())));

        assertEquals(409, exception.getStatusCode().value());
        assertEquals("SUBMISSION_NOT_ALLOWED", exception.getReason());
    }

    @Test
    void rejectsReplacementOfGradedSubmission() {
        ClassMember member = members.findByClassIdAndStudentId(classId, studentId).orElseThrow();
        Submission submission = new Submission(assignmentId, member.getClassMemberId(),
                "old.txt", "old.txt", "text/plain", 1, OffsetDateTime.now(), false);
        submission.grade(java.math.BigDecimal.TEN, "ok", UUID.randomUUID(), OffsetDateTime.now());
        when(submissions.findLockedByAssignmentIdAndClassMemberId(eq(assignmentId), any()))
                .thenReturn(Optional.of(submission));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.submit(assignmentId, studentId,
                        new MockMultipartFile("file", "work.txt", "text/plain", "x".getBytes())));

        assertEquals("SUBMISSION_ALREADY_GRADED", exception.getReason());
    }
}
