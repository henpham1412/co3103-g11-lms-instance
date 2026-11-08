package vn.edu.hcmut.co3103.lms.service.classmanagement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.CompletionMarkRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.LessonRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClassMembershipServiceTest {

    private final ClassRepository classRepository = mock(ClassRepository.class);
    private final ClassMemberRepository classMemberRepository = mock(ClassMemberRepository.class);
    private final LmsUserAccessRepository userAccessRepository = mock(LmsUserAccessRepository.class);
    private final LessonRepository lessonRepository = mock(LessonRepository.class);
    private final CompletionMarkRepository completionMarkRepository = mock(CompletionMarkRepository.class);
    private final ClassMembershipService service = new ClassMembershipService(
            classRepository,
            classMemberRepository,
            userAccessRepository,
            lessonRepository,
            completionMarkRepository
    );

    private final UUID classId = UUID.randomUUID();
    private final UUID tutorId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.of(
                new Class(
                        tutorId,
                        "CS101",
                        "Programming",
                        null,
                        null,
                        1,
                        Class.EnrollmentPolicy.APPROVAL_REQUIRED
                )
        ));
    }

    @Test
    void approvalRequestCanBeCreatedWhenClassIsAtCapacity() {
        when(classMemberRepository.findByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.empty());
        when(classMemberRepository.save(any(ClassMember.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ClassMembershipService.MembershipResult result = service.requestToJoin(classId, studentId);

        assertTrue(result.created());
        assertEquals(ClassMember.MembershipStatus.PENDING, result.membership().getStatus());
        verify(classMemberRepository, never()).countByClassIdAndStatus(any(), any());
    }

    @Test
    void openJoinIsRejectedWhenTheClassHasNoAvailableSeats() {
        Class openClass = new Class(
                tutorId,
                "CS101",
                "Programming",
                null,
                null,
                1,
                Class.EnrollmentPolicy.OPEN
        );
        ReflectionTestUtils.setField(openClass, "classId", classId);
        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.of(openClass));
        when(classMemberRepository.findByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.empty());
        when(classMemberRepository.countByClassIdAndStatus(
                classId,
                ClassMember.MembershipStatus.ACTIVE
        )).thenReturn(1L);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.joinOpenClass(classId, studentId)
        );

        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
        assertEquals("CLASS_FULL", exception.getReason());
    }

    @Test
    void onlyActiveMembershipGrantsLearningAccess() {
        when(classMemberRepository.existsByClassIdAndStudentIdAndStatus(
                classId,
                studentId,
                ClassMember.MembershipStatus.ACTIVE
        )).thenReturn(false);

        assertFalse(service.hasActiveMembership(classId, studentId));
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.requireActiveMembership(classId, studentId)
        );
        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals("MEMBERSHIP_REQUIRED", exception.getReason());
    }

    @Test
    void leavingAClassChangesMembershipToInactiveInsteadOfDeletingIt() {
        ClassMember membership = new ClassMember(
                classId,
                studentId,
                ClassMember.MembershipStatus.ACTIVE,
                null,
                java.time.Instant.now()
        );
        when(classMemberRepository.findByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.of(membership));
        when(classMemberRepository.save(membership)).thenReturn(membership);

        ClassMember result = service.leaveClass(classId, studentId);

        assertEquals(ClassMember.MembershipStatus.INACTIVE, result.getStatus());
        verify(classMemberRepository).save(membership);
        verify(classMemberRepository, never()).delete(any(ClassMember.class));
    }

    @Test
    void tutorCanApprovePendingRequestAndItBecomesActive() {
        ClassMember pending = membership(ClassMember.MembershipStatus.PENDING);
        when(classMemberRepository.findByClassMemberIdAndClassId(pending.getClassMemberId(), classId))
                .thenReturn(Optional.of(pending));
        when(classMemberRepository.countByClassIdAndStatus(classId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(0L);
        when(classMemberRepository.save(pending)).thenReturn(pending);

        ClassMember result = service.approveJoinRequest(classId, pending.getClassMemberId(), tutorId);

        assertEquals(ClassMember.MembershipStatus.ACTIVE, result.getStatus());
        assertNotNull(result.getJoinedAt());
        verify(classMemberRepository).save(pending);
    }

    @Test
    void tutorCanRejectPendingRequestAndRejectedMembershipCanRequestAgain() {
        ClassMember pending = membership(ClassMember.MembershipStatus.PENDING);
        when(classMemberRepository.findByClassMemberIdAndClassId(pending.getClassMemberId(), classId))
                .thenReturn(Optional.of(pending));
        when(classMemberRepository.save(pending)).thenReturn(pending);

        ClassMember rejected = service.rejectJoinRequest(classId, pending.getClassMemberId(), tutorId);

        assertEquals(ClassMember.MembershipStatus.REJECTED, rejected.getStatus());
        when(classMemberRepository.findByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.of(rejected));
        ClassMembershipService.MembershipResult retried = service.requestToJoin(classId, studentId);

        assertFalse(retried.created());
        assertEquals(ClassMember.MembershipStatus.PENDING, retried.membership().getStatus());
        verify(classMemberRepository, org.mockito.Mockito.times(2)).save(rejected);
    }

    @Test
    void studentCanWithdrawPendingRequestAndRejoinLater() {
        ClassMember pending = membership(ClassMember.MembershipStatus.PENDING);
        when(classMemberRepository.findByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.of(pending));
        when(classMemberRepository.save(pending)).thenReturn(pending);

        service.withdrawJoinRequest(classId, studentId);

        assertEquals(ClassMember.MembershipStatus.INACTIVE, pending.getStatus());
        ClassMembershipService.MembershipResult result = service.requestToJoin(classId, studentId);
        assertFalse(result.created());
        assertEquals(ClassMember.MembershipStatus.PENDING, result.membership().getStatus());
    }

    @Test
    void openClassAllowsRejoiningAnInactiveMembership() {
        Class openClass = new Class(tutorId, "CS101", "Programming", null, null, 10,
                Class.EnrollmentPolicy.OPEN);
        ReflectionTestUtils.setField(openClass, "classId", classId);
        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.of(openClass));
        ClassMember inactive = membership(ClassMember.MembershipStatus.INACTIVE);
        when(classMemberRepository.findByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.of(inactive));
        when(classMemberRepository.countByClassIdAndStatus(classId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(0L);
        when(classMemberRepository.save(inactive)).thenReturn(inactive);

        ClassMembershipService.MembershipResult result = service.joinOpenClass(classId, studentId);

        assertFalse(result.created());
        assertEquals(ClassMember.MembershipStatus.ACTIVE, result.membership().getStatus());
    }

    @Test
    void archivedClassRejectsEnrollment() {
        Class archived = new Class(tutorId, "CS101", "Programming", null, null, 10,
                Class.EnrollmentPolicy.APPROVAL_REQUIRED);
        archived.setStatus(Class.ClassStatus.ARCHIVED);
        ReflectionTestUtils.setField(archived, "classId", classId);
        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.of(archived));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.requestToJoin(classId, studentId));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("CLASS_ARCHIVED", error.getReason());
        verify(classMemberRepository, never()).save(any(ClassMember.class));
    }

    @Test
    void approvalRejectsMembershipThatIsNotPending() {
        ClassMember active = membership(ClassMember.MembershipStatus.ACTIVE);
        when(classMemberRepository.findByClassMemberIdAndClassId(active.getClassMemberId(), classId))
                .thenReturn(Optional.of(active));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.approveJoinRequest(classId, active.getClassMemberId(), tutorId));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("INVALID_MEMBERSHIP_STATE", error.getReason());
        verify(classMemberRepository, never()).save(any(ClassMember.class));
    }

    @Test
    void rejectionRejectsMembershipThatIsNotPending() {
        ClassMember inactive = membership(ClassMember.MembershipStatus.INACTIVE);
        when(classMemberRepository.findByClassMemberIdAndClassId(inactive.getClassMemberId(), classId))
                .thenReturn(Optional.of(inactive));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.rejectJoinRequest(classId, inactive.getClassMemberId(), tutorId));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("INVALID_MEMBERSHIP_STATE", error.getReason());
        verify(classMemberRepository, never()).save(any(ClassMember.class));
    }

    @Test
    void studentCannotLeaveWhileMembershipIsPending() {
        ClassMember pending = membership(ClassMember.MembershipStatus.PENDING);
        when(classMemberRepository.findByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.of(pending));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.leaveClass(classId, studentId));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("INVALID_MEMBERSHIP_STATE", error.getReason());
        verify(classMemberRepository, never()).save(any(ClassMember.class));
    }

    @Test
    void studentCannotWithdrawWhenThereIsNoPendingRequest() {
        ClassMember active = membership(ClassMember.MembershipStatus.ACTIVE);
        when(classMemberRepository.findByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.of(active));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.withdrawJoinRequest(classId, studentId));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("INVALID_MEMBERSHIP_STATE", error.getReason());
        verify(classMemberRepository, never()).save(any(ClassMember.class));
    }

    @Test
    void tutorCannotRejectAnotherTutorsMembership() {
        Class anotherTutorsClass = new Class(UUID.randomUUID(), "CS202", "Other class", null, null, 10,
                Class.EnrollmentPolicy.APPROVAL_REQUIRED);
        ReflectionTestUtils.setField(anotherTutorsClass, "classId", classId);
        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.of(anotherTutorsClass));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.rejectJoinRequest(classId, UUID.randomUUID(), tutorId));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(classMemberRepository, never()).findByClassMemberIdAndClassId(any(), any());
        verify(classMemberRepository, never()).save(any(ClassMember.class));
    }

    @Test
    void tutorCannotRemovePendingRequestAsIfItWereAnActiveMember() {
        ClassMember pending = membership(ClassMember.MembershipStatus.PENDING);
        when(classMemberRepository.findByClassMemberIdAndClassId(pending.getClassMemberId(), classId))
                .thenReturn(Optional.of(pending));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.removeMember(classId, pending.getClassMemberId(), tutorId));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("INVALID_MEMBERSHIP_STATE", error.getReason());
        verify(classMemberRepository, never()).save(any(ClassMember.class));
    }

    @Test
    void tutorCanRemoveActiveMemberAndRetainMembershipRecord() {
        ClassMember active = membership(ClassMember.MembershipStatus.ACTIVE);
        when(classMemberRepository.findByClassMemberIdAndClassId(active.getClassMemberId(), classId))
                .thenReturn(Optional.of(active));
        when(classMemberRepository.save(active)).thenReturn(active);

        ClassMember removed = service.removeMember(classId, active.getClassMemberId(), tutorId);

        assertEquals(ClassMember.MembershipStatus.INACTIVE, removed.getStatus());
        verify(classMemberRepository).save(active);
        verify(classMemberRepository, never()).delete(any(ClassMember.class));
    }

    @Test
    void openJoinRejectsWhenEnrollmentPolicyChanged() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.joinOpenClass(classId, studentId));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("ENROLLMENT_POLICY_CHANGED", error.getReason());
        verify(classMemberRepository, never()).save(any(ClassMember.class));
    }

    @Test
    void approvalRequestRejectsWhenEnrollmentPolicyChanged() {
        Class openClass = new Class(tutorId, "CS101", "Programming", null, null, 10,
                Class.EnrollmentPolicy.OPEN);
        ReflectionTestUtils.setField(openClass, "classId", classId);
        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.of(openClass));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.requestToJoin(classId, studentId));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("ENROLLMENT_POLICY_CHANGED", error.getReason());
        verify(classMemberRepository, never()).save(any(ClassMember.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "OPEN, ACTIVE", "OPEN, PENDING", "APPROVAL_REQUIRED, ACTIVE", "APPROVAL_REQUIRED, PENDING"
    })
    void enrollmentRejectsDuplicateActiveOrPendingMemberships(String policyName, String statusName) {
        Class.EnrollmentPolicy policy = Class.EnrollmentPolicy.valueOf(policyName);
        Class configuredClass = new Class(tutorId, "CS101", "Programming", null, null, 10, policy);
        ReflectionTestUtils.setField(configuredClass, "classId", classId);
        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.of(configuredClass));
        ClassMember existing = membership(ClassMember.MembershipStatus.valueOf(statusName));
        when(classMemberRepository.findByClassIdAndStudentId(classId, studentId))
                .thenReturn(Optional.of(existing));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> {
            if (policy == Class.EnrollmentPolicy.OPEN) {
                service.joinOpenClass(classId, studentId);
            } else {
                service.requestToJoin(classId, studentId);
            }
        });

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("INVALID_MEMBERSHIP_STATE", error.getReason());
        verify(classMemberRepository, never()).save(any(ClassMember.class));
    }
    @Test
    void onlyOwningTutorCanSearchTheRoster() {
        Class otherTutorsClass = new Class(UUID.randomUUID(), "CS202", "Other class", null, null, 10,
                Class.EnrollmentPolicy.OPEN);
        when(classRepository.findById(classId)).thenReturn(Optional.of(otherTutorsClass));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.listMembers(classId, tutorId, "ada", null,
                        org.springframework.data.domain.PageRequest.of(0, 20)));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(classMemberRepository, never()).searchRoster(any(), any(), any(), any());
    }

    @Test
    void rosterSearchNormalizesQueryAndKeepsStatusFilterPageAndSort() {
        when(classRepository.findById(classId)).thenReturn(Optional.of(
                new Class(tutorId, "CS101", "Programming", null, null, 10, Class.EnrollmentPolicy.OPEN)));
        var pageable = org.springframework.data.domain.PageRequest.of(1, 20,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC,
                        "joined_at"));
        when(classMemberRepository.searchRoster(classId, "ACTIVE", "ada lovelace", pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(), pageable, 0L));

        service.listMembers(classId, tutorId, "  ada lovelace  ",
                ClassMember.MembershipStatus.ACTIVE, pageable);

        verify(classMemberRepository).searchRoster(classId, "ACTIVE", "ada lovelace", pageable);
    }

    @Test
    void rosterRejectsOverlongSearchWithoutQueryingTheDatabase() {
        when(classRepository.findById(classId)).thenReturn(Optional.of(
                new Class(tutorId, "CS101", "Programming", null, null, 10, Class.EnrollmentPolicy.OPEN)));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.listMembers(classId, tutorId, "x".repeat(101), null,
                        org.springframework.data.domain.PageRequest.of(0, 20)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertEquals("INVALID_QUERY_PARAMETER", error.getReason());
        verify(classMemberRepository, never()).searchRoster(any(), any(), any(), any());
    }

    @Test
    void listStudentClassesReturnsZeroPercentForEmptyClassAndNullForPendingMembership() {
        Class openClass = new Class(tutorId, "CS101", "Programming", null, null, 10, Class.EnrollmentPolicy.OPEN);
        ReflectionTestUtils.setField(openClass, "classId", classId);
        when(classRepository.findById(classId)).thenReturn(Optional.of(openClass));

        ClassMember active = membership(ClassMember.MembershipStatus.ACTIVE);
        ClassMember pending = membership(ClassMember.MembershipStatus.PENDING);
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        when(classMemberRepository.findStudentClassHistory(studentId, "ACTIVE", pageable))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(active, pending), pageable, 2));
        when(lessonRepository.findEligibleLessons(classId, vn.edu.hcmut.co3103.lms.entity.Chapter.VisibilityStatus.PUBLISHED))
                .thenReturn(List.of());
        when(completionMarkRepository.findByClassMemberId(active.getClassMemberId())).thenReturn(List.of());

        var result = service.listStudentClasses(studentId, Class.ClassStatus.ACTIVE, pageable);

        assertEquals(2, result.getContent().size());
        var activeItem = result.getContent().get(0);
        assertEquals("ACTIVE", activeItem.membershipStatus());
        assertNotNull(activeItem.progress());
        assertEquals(0, activeItem.progress().completedLessons());
        assertEquals(0, activeItem.progress().eligibleLessons());
        assertEquals(0, activeItem.progress().progressPercentage());
        assertEquals("IN_PROGRESS", activeItem.progress().status());

        var pendingItem = result.getContent().get(1);
        assertEquals("PENDING", pendingItem.membershipStatus());
        assertNull(pendingItem.progress());
    }

    private ClassMember membership(ClassMember.MembershipStatus status) {
        ClassMember result = new ClassMember(classId, studentId, status,
                java.time.Instant.now(), status == ClassMember.MembershipStatus.ACTIVE
                ? java.time.Instant.now() : null);
        ReflectionTestUtils.setField(result, "classMemberId", UUID.randomUUID());
        return result;
    }
}
