package vn.edu.hcmut.co3103.lms.service.assessment;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import java.util.UUID;

/** Canonical lock hierarchy: Class, then Quiz, then Attempt/Question/answers. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class AssessmentLocks {
    private final EntityManager entityManager;
    private final ClassRepository classes;

    public void lockQuiz(UUID quizId) {
        lockScope("select c.class_id from quiz q join activity a on a.activity_id=q.activity_id join lesson l on l.lesson_id=a.lesson_id join chapter c on c.chapter_id=l.chapter_id where q.quiz_id=:id", quizId);
    }
    public void lockActivity(UUID activityId) {
        lockScope("select c.class_id from activity a join lesson l on l.lesson_id=a.lesson_id join chapter c on c.chapter_id=l.chapter_id where a.activity_id=:id", activityId);
    }
    private void lockScope(String sql, UUID id) {
        var ids = entityManager.createNativeQuery(sql, UUID.class).setParameter("id", id).getResultList();
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "QUIZ_NOT_FOUND");
        classes.findByIdForUpdate((UUID) ids.getFirst())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CLASS_NOT_FOUND"));
    }
}
