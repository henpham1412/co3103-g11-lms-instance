package vn.edu.hcmut.co3103.lms.repository.classmanagement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.ClassMember.MembershipStatus;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface ClassMemberRepository
        extends JpaRepository<ClassMember, UUID> {

    Optional<ClassMember> findByClassIdAndStudentId(
            UUID classId,
            UUID studentId
    );

    boolean existsByClassIdAndStudentIdAndStatus(
            UUID classId,
            UUID studentId,
            MembershipStatus status
    );

    Optional<ClassMember> findByClassMemberIdAndClassId(
            UUID classMemberId,
            UUID classId
    );

    Page<ClassMember> findByClassId(UUID classId, Pageable pageable);

    Page<ClassMember> findByClassIdAndStatus(
            UUID classId,
            MembershipStatus status,
            Pageable pageable
    );

    Page<ClassMember> findByStudentIdAndStatus(
            UUID studentId,
            MembershipStatus status,
            Pageable pageable
    );

    List<ClassMember> findByStudentIdAndStatusInOrderByRequestedAtDesc(
            UUID studentId,
            java.util.Collection<MembershipStatus> statuses
    );

    long countByClassIdAndStatus(
            UUID classId,
            MembershipStatus status
    );
}
