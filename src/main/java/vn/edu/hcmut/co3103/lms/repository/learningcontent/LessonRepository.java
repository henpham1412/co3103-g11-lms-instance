package vn.edu.hcmut.co3103.lms.repository.learningcontent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LessonRepository extends JpaRepository<Lesson, UUID> {
    List<Lesson> findByChapterIdOrderBySequenceOrderAsc(UUID chapterId);
    List<Lesson> findByChapterIdAndStatusOrderBySequenceOrderAsc(UUID chapterId, Chapter.VisibilityStatus status);
    Optional<Lesson> findByLessonIdAndChapterId(UUID lessonId, UUID chapterId);
    boolean existsByChapterIdAndLessonId(UUID chapterId, UUID lessonId);

    @Query("select coalesce(max(l.sequenceOrder) + 1, 0) from Lesson l where l.chapterId = :chapterId")
    int nextSequenceOrderForChapter(@Param("chapterId") UUID chapterId);
}
