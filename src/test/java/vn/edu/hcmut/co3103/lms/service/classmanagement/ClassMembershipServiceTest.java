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
}
