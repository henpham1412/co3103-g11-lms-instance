package vn.edu.hcmut.co3103.lms.service.classmanagement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClassMembershipServiceTest {

    private final ClassRepository classRepository = mock(ClassRepository.class);
    private final ClassMemberRepository classMemberRepository = mock(ClassMemberRepository.class);
    private final ClassMembershipService service = new ClassMembershipService(
            classRepository,
            classMemberRepository
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
        assertTrue(result.getJoinedAt() != null);
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

    private ClassMember membership(ClassMember.MembershipStatus status) {
        ClassMember result = new ClassMember(classId, studentId, status,
                java.time.Instant.now(), status == ClassMember.MembershipStatus.ACTIVE
                ? java.time.Instant.now() : null);
        ReflectionTestUtils.setField(result, "classMemberId", UUID.randomUUID());
        return result;
    }
}
