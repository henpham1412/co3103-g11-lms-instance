package vn.edu.hcmut.co3103.lms.service.classmanagement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.ClassMember.MembershipStatus;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;

import java.time.Instant;
import java.util.UUID;

@Service
public class ClassMembershipService {

    private final ClassRepository classRepository;
    private final ClassMemberRepository classMemberRepository;

    public ClassMembershipService(
            ClassRepository classRepository,
            ClassMemberRepository classMemberRepository
    ) {
        this.classRepository = classRepository;
        this.classMemberRepository = classMemberRepository;
    }

    /**
     * Checks only the class-membership condition for access to learning activities.
     * Assessment eligibility also depends on activity visibility, class state,
     * quiz availability times, and the learner's remaining attempts.
     */
    @Transactional(readOnly = true)
    public boolean hasActiveMembership(UUID classId, UUID studentId) {
        return classMemberRepository.existsByClassIdAndStudentIdAndStatus(
                classId,
                studentId,
                MembershipStatus.ACTIVE
        );
    }

    @Transactional
    public ClassMember requestToJoin(UUID classId, UUID studentId) {
        Class targetClass = requireClassForUpdate(classId);
        ensureClassCanEnroll(targetClass);

        if (targetClass.getEnrollmentPolicy() != Class.EnrollmentPolicy.APPROVAL_REQUIRED) {
            throw conflict("ENROLLMENT_POLICY_CHANGED");
        }

        Instant now = Instant.now();
        ClassMember membership = classMemberRepository
                .findByClassIdAndStudentId(classId, studentId)
                .orElse(null);

        if (membership == null) {
            membership = new ClassMember(
                    classId,
                    studentId,
                    MembershipStatus.PENDING,
                    now,
                    null
            );
        } else {
            ensureReusableMembership(membership);
            membership.setStatus(MembershipStatus.PENDING);
            membership.setRequestedAt(now);
            membership.setJoinedAt(null);
        }

        return classMemberRepository.save(membership);
    }

    @Transactional
    public ClassMember joinOpenClass(UUID classId, UUID studentId) {
        Class targetClass = requireClassForUpdate(classId);
        ensureClassCanEnroll(targetClass);

        if (targetClass.getEnrollmentPolicy() != Class.EnrollmentPolicy.OPEN) {
            throw conflict("ENROLLMENT_POLICY_CHANGED");
        }

        ClassMember membership = classMemberRepository
                .findByClassIdAndStudentId(classId, studentId)
                .orElse(null);

        if (membership != null) {
            ensureReusableMembership(membership);
        }
        ensureHasCapacity(targetClass);

        Instant now = Instant.now();
        if (membership == null) {
            membership = new ClassMember(
                    classId,
                    studentId,
                    MembershipStatus.ACTIVE,
                    null,
                    now
            );
        } else {
            membership.setStatus(MembershipStatus.ACTIVE);
            membership.setRequestedAt(null);
            membership.setJoinedAt(now);
        }

        return classMemberRepository.save(membership);
    }

    @Transactional(readOnly = true)
    public Page<ClassMember> listMembers(
            UUID classId,
            UUID tutorId,
            MembershipStatus status,
            Pageable pageable
    ) {
        Class targetClass = requireClass(classId);
        ensureTutorOwnsClass(targetClass, tutorId);

        if (status == null) {
            return classMemberRepository.findByClassId(classId, pageable);
        }
        return classMemberRepository.findByClassIdAndStatus(classId, status, pageable);
    }

    @Transactional
    public ClassMember approveJoinRequest(
            UUID classId,
            UUID classMemberId,
            UUID tutorId
    ) {
        Class targetClass = requireOwnedClassForUpdate(classId, tutorId);
        ensureClassCanEnroll(targetClass);

        ClassMember membership = requireMembership(classId, classMemberId);
        ensureStatus(membership, MembershipStatus.PENDING);
        ensureHasCapacity(targetClass);

        membership.setStatus(MembershipStatus.ACTIVE);
        membership.setJoinedAt(Instant.now());
        return classMemberRepository.save(membership);
    }

    @Transactional
    public ClassMember rejectJoinRequest(
            UUID classId,
            UUID classMemberId,
            UUID tutorId
    ) {
        requireOwnedClassForUpdate(classId, tutorId);
        ClassMember membership = requireMembership(classId, classMemberId);
        ensureStatus(membership, MembershipStatus.PENDING);

        membership.setStatus(MembershipStatus.REJECTED);
        membership.setJoinedAt(null);
        return classMemberRepository.save(membership);
    }

    @Transactional
    public ClassMember withdrawJoinRequest(UUID classId, UUID studentId) {
        requireClassForUpdate(classId);
        ClassMember membership = requireStudentMembership(classId, studentId);
        ensureStatus(membership, MembershipStatus.PENDING);

        membership.setStatus(MembershipStatus.INACTIVE);
        membership.setJoinedAt(null);
        return classMemberRepository.save(membership);
    }

    @Transactional
    public ClassMember leaveClass(UUID classId, UUID studentId) {
        requireClassForUpdate(classId);
        ClassMember membership = requireStudentMembership(classId, studentId);
        ensureStatus(membership, MembershipStatus.ACTIVE);

        membership.setStatus(MembershipStatus.INACTIVE);
        return classMemberRepository.save(membership);
    }

    @Transactional
    public ClassMember removeMember(
            UUID classId,
            UUID classMemberId,
            UUID tutorId
    ) {
        requireOwnedClassForUpdate(classId, tutorId);
        ClassMember membership = requireMembership(classId, classMemberId);
        ensureStatus(membership, MembershipStatus.ACTIVE);

        membership.setStatus(MembershipStatus.INACTIVE);
        return classMemberRepository.save(membership);
    }

    private Class requireClass(UUID classId) {
        return classRepository.findById(classId)
                .orElseThrow(() -> notFound("CLASS_OR_MEMBERSHIP_NOT_FOUND"));
    }

    private Class requireClassForUpdate(UUID classId) {
        return classRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> notFound("CLASS_OR_MEMBERSHIP_NOT_FOUND"));
    }

    private Class requireOwnedClassForUpdate(UUID classId, UUID tutorId) {
        Class targetClass = requireClassForUpdate(classId);
        ensureTutorOwnsClass(targetClass, tutorId);
        return targetClass;
    }

    private void ensureTutorOwnsClass(Class targetClass, UUID tutorId) {
        if (!targetClass.getTutorId().equals(tutorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
    }

    private ClassMember requireMembership(UUID classId, UUID classMemberId) {
        return classMemberRepository.findByClassMemberIdAndClassId(classMemberId, classId)
                .orElseThrow(() -> notFound("CLASS_OR_MEMBERSHIP_NOT_FOUND"));
    }

    private ClassMember requireStudentMembership(UUID classId, UUID studentId) {
        return classMemberRepository.findByClassIdAndStudentId(classId, studentId)
                .orElseThrow(() -> notFound("CLASS_OR_MEMBERSHIP_NOT_FOUND"));
    }

    private void ensureClassCanEnroll(Class targetClass) {
        if (targetClass.getStatus() == Class.ClassStatus.ARCHIVED) {
            throw conflict("CLASS_ARCHIVED");
        }
    }

    private void ensureHasCapacity(Class targetClass) {
        Integer maxCapacity = targetClass.getMaxCapacity();
        if (maxCapacity == null) {
            return;
        }

        long activeCount = classMemberRepository.countByClassIdAndStatus(
                targetClass.getClassId(),
                MembershipStatus.ACTIVE
        );
        if (activeCount >= maxCapacity) {
            throw conflict("CLASS_FULL");
        }
    }

    private void ensureReusableMembership(ClassMember membership) {
        if (membership.getStatus() == MembershipStatus.ACTIVE
                || membership.getStatus() == MembershipStatus.PENDING) {
            throw conflict("INVALID_MEMBERSHIP_STATE");
        }
    }

    private void ensureStatus(ClassMember membership, MembershipStatus expectedStatus) {
        if (membership.getStatus() != expectedStatus) {
            throw conflict("INVALID_MEMBERSHIP_STATE");
        }
    }

    private ResponseStatusException notFound(String errorCode) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, errorCode);
    }

    private ResponseStatusException conflict(String errorCode) {
        return new ResponseStatusException(HttpStatus.CONFLICT, errorCode);
    }
}
