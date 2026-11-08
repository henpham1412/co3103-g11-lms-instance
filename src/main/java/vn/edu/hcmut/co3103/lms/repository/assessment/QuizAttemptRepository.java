package vn.edu.hcmut.co3103.lms.repository.assessment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.hcmut.co3103.lms.entity.QuizAttempt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.OffsetDateTime;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, UUID> {
    boolean existsByQuiz_Id(UUID quizId);
    List<QuizAttempt> findByQuiz_IdAndStatus(UUID quizId, String status);
    List<QuizAttempt> findByQuiz_IdAndClassMember_ClassMemberIdOrderByAttemptNumber(UUID quizId, UUID classMemberId);

    @EntityGraph(attributePaths = {"classMember", "quiz", "quiz.activity"})
    List<QuizAttempt> findByClassMember_ClassMemberId(UUID classMemberId);

    Page<QuizAttempt> findByQuiz_Id(UUID quizId, Pageable pageable);

    @Query(value = """
            select attempt from QuizAttempt attempt
            join fetch attempt.classMember member
            join fetch attempt.quiz quiz
            join fetch quiz.activity activity
            where member.studentId = :studentId
              and (:classId is null or member.classId = :classId)
            """,
            countQuery = """
                    select count(attempt) from QuizAttempt attempt
                    join attempt.classMember member
                    where member.studentId = :studentId
                      and (:classId is null or member.classId = :classId)
                    """)
    Page<QuizAttempt> findStudentHistory(@Param("studentId") UUID studentId,
                                        @Param("classId") UUID classId,
                                        Pageable pageable);

    @Query("select attempt.quiz.id from QuizAttempt attempt where attempt.id = :attemptId")
    Optional<UUID> findQuizIdByAttemptId(@Param("attemptId") UUID attemptId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    java.util.Optional<QuizAttempt> findLockedById(UUID attemptId);

    @Query(value = """
            SELECT qa.attempt_id
            FROM quiz_attempt qa
            JOIN quiz q ON q.quiz_id = qa.quiz_id
            WHERE qa.status = 'IN_PROGRESS'
              AND (CAST(:afterId AS uuid) IS NULL OR qa.attempt_id > CAST(:afterId AS uuid))
              AND (qa.started_at + (q.time_limit_minutes * INTERVAL '1 minute') <= :asOf
                   OR q.deadline <= :asOf)
            ORDER BY qa.attempt_id
            LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findExpiredAttemptIdsAfter(@Param("asOf") OffsetDateTime asOf,
                                         @Param("afterId") UUID afterId,
                                         @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT qa.attempt_id
            FROM quiz_attempt qa
            WHERE qa.status = 'SUBMITTED'
              AND (CAST(:afterId AS uuid) IS NULL OR qa.attempt_id > CAST(:afterId AS uuid))
              AND (qa.score IS NULL OR qa.graded_at IS NULL)
            ORDER BY qa.attempt_id
            LIMIT :batchSize
            """, nativeQuery = true)
    List<UUID> findSubmittedUngradedAttemptIdsAfter(@Param("afterId") UUID afterId,
                                                   @Param("batchSize") int batchSize);
}
