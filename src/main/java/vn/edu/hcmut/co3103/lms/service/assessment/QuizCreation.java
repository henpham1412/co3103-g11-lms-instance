package vn.edu.hcmut.co3103.lms.service.assessment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import vn.edu.hcmut.co3103.lms.dto.request.QuestionCreateRequest;

/** Full owner command, distinct from the presence-aware PATCH request. */
public record QuizCreation(Integer timeLimitMinutes, Integer attemptsAllowed, String gradingMethod,
        OffsetDateTime openingTime, OffsetDateTime deadline, BigDecimal requiredScore,
        List<QuestionCreateRequest> questions) { }
