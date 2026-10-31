package vn.edu.hcmut.co3103.lms.service.assessment;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;

import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class QuizAttemptExpiryScheduler {
    private static final int BATCH_SIZE = 100;

    private final QuizAttemptRepository attemptRepository;
    private final QuizAttemptService attemptService;

    @Scheduled(fixedDelayString = "${lms.quiz.expiry-scan-ms:1000}")
    public void submitExpiredAttempts() {
        var expiredIds = attemptRepository.findExpiredAttemptIds(OffsetDateTime.now(), BATCH_SIZE);
        for (var attemptId : expiredIds) {
            attemptService.expireIfDue(attemptId);
        }
    }
}

