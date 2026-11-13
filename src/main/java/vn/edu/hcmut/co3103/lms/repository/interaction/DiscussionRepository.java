package vn.edu.hcmut.co3103.lms.repository.interaction;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.hcmut.co3103.lms.entity.Discussion;

import java.util.List;
import java.util.UUID;

public interface DiscussionRepository extends JpaRepository<Discussion, UUID> {

    boolean existsByParentId(UUID parentId);

    @Query("select count(d) from Discussion d where d.classId = :classId and d.parentId is null "
            + "and (:lessonId is null or d.lessonId = :lessonId)")
    long countRootThreads(@Param("classId") UUID classId, @Param("lessonId") UUID lessonId);

    /**
     * UC 6.2 orders threads by recent activity, which is the newest post anywhere in the thread.
     * Replies may nest, so the activity of a root is the maximum over its whole subtree.
     */
    @Query(value = """
            WITH RECURSIVE thread AS (
                SELECT root.discussion_id AS root_id, root.discussion_id, root.created_at
                FROM discussion root
                WHERE root.class_id = :classId
                  AND root.parent_id IS NULL
                  AND (:lessonId IS NULL OR root.lesson_id = :lessonId)
                UNION ALL
                SELECT parent.root_id, child.discussion_id, child.created_at
                FROM discussion child
                JOIN thread parent ON child.parent_id = parent.discussion_id
            )
            SELECT thread.root_id
            FROM thread
            GROUP BY thread.root_id
            ORDER BY MAX(thread.created_at) DESC, thread.root_id
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<UUID> findRootIdsByRecentActivity(@Param("classId") UUID classId,
                                           @Param("lessonId") UUID lessonId,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);
}