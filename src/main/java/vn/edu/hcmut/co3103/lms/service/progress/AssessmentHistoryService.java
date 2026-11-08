package vn.edu.hcmut.co3103.lms.service.progress;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.response.AssessmentHistoryItem;
import vn.edu.hcmut.co3103.lms.dto.response.PageResponse;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;

import java.util.UUID;

@Service
public class AssessmentHistoryService {
    private final QuizAttemptRepository quizAttemptRepository;

    public AssessmentHistoryService(QuizAttemptRepository quizAttemptRepository) {
        this.quizAttemptRepository = quizAttemptRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<AssessmentHistoryItem> getHistory(
            UUID studentId,
            UUID classId,
            String type,
            int page,
            int size
    ) {
        if (page < 1 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUERY_PARAMETER");
        }
        if (type != null && !type.equals("QUIZ") && !type.equals("ASSIGNMENT")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUERY_PARAMETER");
        }
        Pageable pageable = PageRequest.of(
                page - 1,
                size,
                Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"))
        );
        Page<AssessmentHistoryItem> items = "ASSIGNMENT".equals(type)
                ? Page.empty(pageable)
                : quizAttemptRepository.findStudentHistory(studentId, classId, pageable)
                        .map(AssessmentHistoryItem::from);
        return PageResponse.from(items);
    }
}
