package vn.edu.hcmut.co3103.lms.repository.learningcontent;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Material;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaterialRepository extends JpaRepository<Material, UUID> {
    List<Material> findByLessonIdOrderBySequenceOrderAsc(UUID lessonId);
    List<Material> findByLessonIdAndStatusOrderBySequenceOrderAsc(UUID lessonId, Chapter.VisibilityStatus status);
    Optional<Material> findByMaterialIdAndLessonId(UUID materialId, UUID lessonId);
}
