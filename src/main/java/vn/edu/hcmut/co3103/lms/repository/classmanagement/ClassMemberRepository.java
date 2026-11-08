package vn.edu.hcmut.co3103.lms.repository.classmanagement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.ClassMember.MembershipStatus;
import vn.edu.hcmut.co3103.lms.entity.Class;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClassMemberRepository
        extends JpaRepository<ClassMember, UUID> {

    Optional<ClassMember> findByClassIdAndStudentId(
            UUID classId,
            UUID studentId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select cm from ClassMember cm where cm.classId = :classId and cm.studentId = :studentId")
    Optional<ClassMember> findLockedByClassIdAndStudentId(
            @Param("classId") UUID classId,
            @Param("studentId") UUID studentId
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

    List<ClassMember> findByClassIdAndStatusOrderByJoinedAtAsc(
            UUID classId,
            MembershipStatus status
    );

    @Query(value = """
            SELECT cm.*
            FROM class_member cm
            LEFT JOIN lms_user_access u ON u.user_id = cm.student_id
            WHERE cm.class_id = :classId
              AND (:status IS NULL OR cm.status = :status)
              AND (:q IS NULL
                   OR LOWER(u.full_name) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%')))
            """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM class_member cm
                    LEFT JOIN lms_user_access u ON u.user_id = cm.student_id
                    WHERE cm.class_id = :classId
                      AND (:status IS NULL OR cm.status = :status)
                      AND (:q IS NULL
                           OR LOWER(u.full_name) LIKE LOWER(CONCAT('%', :q, '%'))
                           OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%')))
                    """,
            nativeQuery = true
    )
    Page<ClassMember> searchRoster(
            @Param("classId") UUID classId,
            @Param("status") String status,
            @Param("q") String query,
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

    @Query(value = """
            SELECT cm.*
            FROM class_member cm
            JOIN class c ON c.class_id = cm.class_id
            WHERE cm.student_id = :studentId
              AND cm.status IN ('ACTIVE', 'PENDING')
              AND (:classStatus IS NULL OR c.status = :classStatus)
            ORDER BY c.created_at DESC, cm.requested_at DESC NULLS LAST, cm.class_member_id
            """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM class_member cm
                    JOIN class c ON c.class_id = cm.class_id
                    WHERE cm.student_id = :studentId
                      AND cm.status IN ('ACTIVE', 'PENDING')
                      AND (:classStatus IS NULL OR c.status = :classStatus)
                    """,
            nativeQuery = true
    )
    Page<ClassMember> findStudentClassHistory(
            @Param("studentId") UUID studentId,
            @Param("classStatus") String classStatus,
            Pageable pageable
    );

    long countByClassIdAndStatus(
            UUID classId,
            MembershipStatus status
    );
}
