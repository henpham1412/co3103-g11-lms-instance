package vn.edu.hcmut.co3103.lms.repository.learningcontent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Material;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaterialRepository extends JpaRepository<Material, UUID> {
    List<Material> findByLessonIdOrderBySequenceOrderAsc(UUID lessonId);
    List<Material> findByLessonIdAndStatusOrderBySequenceOrderAsc(UUID lessonId, Chapter.VisibilityStatus status);
    Optional<Material> findByMaterialIdAndLessonId(UUID materialId, UUID lessonId);
    Optional<Material> findByFileUrl(String fileUrl);
    boolean existsByFileUrlAndMaterialIdNot(String fileUrl, UUID materialId);

    @Query("select c.classId from Material m, Lesson l, Chapter c where m.materialId = :materialId "
            + "and m.lessonId = l.lessonId and l.chapterId = c.chapterId")
    Optional<UUID> findClassIdByMaterialId(@Param("materialId") UUID materialId);

    @Query("select coalesce(max(m.sequenceOrder) + 1, 0) from Material m where m.lessonId = :lessonId")
    int nextSequenceOrderForLesson(@Param("lessonId") UUID lessonId);
}
