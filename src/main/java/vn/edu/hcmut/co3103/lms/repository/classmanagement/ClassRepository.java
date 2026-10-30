package vn.edu.hcmut.co3103.lms.repository.classmanagement;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.hcmut.co3103.lms.entity.Class;

import java.util.Optional;
import java.util.UUID;

public interface ClassRepository extends JpaRepository<Class, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Class c where c.classId = :classId")
    Optional<Class> findByIdForUpdate(@Param("classId") UUID classId);

    @Query(value = """
            SELECT c.class_id AS "classId",
                   c.class_code AS "classCode",
                   c.title AS "title",
                   c.description AS "description",
                   c.prerequisites_description AS "prerequisitesDescription",
                   c.thumbnail_url AS "thumbnailUrl",
                   c.enrollment_policy AS "enrollmentPolicy",
                   c.status AS "status",
                   u.user_id AS "tutorId",
                   u.full_name AS "tutorName",
                   (SELECT COUNT(*)
                    FROM class_members cm
                    WHERE cm.class_id = c.class_id
                      AND cm.status = 'ACTIVE') AS "activeMemberCount",
                   c.max_capacity AS "maxCapacity"
            FROM classes c
            JOIN lms_user_access u ON u.user_id = c.tutor_id
            WHERE c.status = 'ACTIVE'
              AND u.access_status = 'ACTIVE'
              AND u.lms_role = 'TUTOR'
              AND (:q IS NULL
                   OR LOWER(c.title) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(c.class_code) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR LOWER(u.full_name) LIKE LOWER(CONCAT('%', :q, '%')))
              AND (:enrollmentPolicy IS NULL OR c.enrollment_policy = :enrollmentPolicy)
            """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM classes c
                    JOIN lms_user_access u ON u.user_id = c.tutor_id
                    WHERE c.status = 'ACTIVE'
                      AND u.access_status = 'ACTIVE'
                      AND u.lms_role = 'TUTOR'
                      AND (:q IS NULL
                           OR LOWER(c.title) LIKE LOWER(CONCAT('%', :q, '%'))
                           OR LOWER(c.class_code) LIKE LOWER(CONCAT('%', :q, '%'))
                           OR LOWER(u.full_name) LIKE LOWER(CONCAT('%', :q, '%')))
                      AND (:enrollmentPolicy IS NULL OR c.enrollment_policy = :enrollmentPolicy)
                    """,
            nativeQuery = true
    )
    Page<ClassCatalogRow> searchCatalog(
            @Param("q") String query,
            @Param("enrollmentPolicy") String enrollmentPolicy,
            Pageable pageable
    );

    boolean existsByClassCode(String classCode);

    Optional<Class> findByClassCode(String classCode);

    Page<Class> findByStatus(
            Class.ClassStatus status,
            Pageable pageable
    );

    Page<Class> findByTutorId(
            UUID tutorId,
            Pageable pageable
    );
}