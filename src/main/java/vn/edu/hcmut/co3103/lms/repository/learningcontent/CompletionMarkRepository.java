package vn.edu.hcmut.co3103.lms.repository.learningcontent;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.CompletionMark;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompletionMarkRepository extends JpaRepository<CompletionMark, UUID> {
    List<CompletionMark> findByClassMemberId(UUID classMemberId);
    Optional<CompletionMark> findByClassMemberIdAndLessonId(UUID classMemberId, UUID lessonId);
}
