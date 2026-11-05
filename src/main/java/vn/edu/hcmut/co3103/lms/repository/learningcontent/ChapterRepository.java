package vn.edu.hcmut.co3103.lms.repository.learningcontent;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChapterRepository extends JpaRepository<Chapter, UUID> {
    List<Chapter> findByClassIdOrderBySequenceOrderAsc(UUID classId);
    List<Chapter> findByClassIdAndStatusOrderBySequenceOrderAsc(UUID classId, Chapter.VisibilityStatus status);
    Optional<Chapter> findByChapterIdAndClassId(UUID chapterId, UUID classId);
    boolean existsByClassIdAndChapterId(UUID classId, UUID chapterId);
}
