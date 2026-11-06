package vn.edu.hcmut.co3103.lms.repository.learningcontent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.hcmut.co3103.lms.entity.Activity;
import vn.edu.hcmut.co3103.lms.entity.Chapter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivityRepository extends JpaRepository<Activity, UUID> {
    List<Activity> findByLessonIdOrderBySequenceOrderAsc(UUID lessonId);
    List<Activity> findByLessonIdAndStatusOrderBySequenceOrderAsc(UUID lessonId, Chapter.VisibilityStatus status);
    Optional<Activity> findByActivityIdAndLessonId(UUID activityId, UUID lessonId);

    @Query("select c.classId from Activity a, Lesson l, Chapter c where a.activityId = :activityId "
            + "and a.lessonId = l.lessonId and l.chapterId = c.chapterId")
    Optional<UUID> findClassIdByActivityId(@Param("activityId") UUID activityId);

    @Query("select coalesce(max(a.sequenceOrder) + 1, 0) from Activity a where a.lessonId = :lessonId")
    int nextSequenceOrderForLesson(@Param("lessonId") UUID lessonId);
}
