package vn.edu.hcmut.co3103.lms.service.assessment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class QuizAttemptExpiryScheduler {
    private static final int BATCH_SIZE = 100;

    private final QuizAttemptRepository attemptRepository;
    private final QuizAttemptService attemptService;
    private UUID expiredCursor;
    private UUID ungradedCursor;

    @Scheduled(fixedDelayString = "${lms.quiz.expiry-scan-ms:1000}")
    public void submitExpiredAttempts() {
        List<UUID> expiredIds = nextExpiredBatch(OffsetDateTime.now());
        Set<UUID> processedIds = new HashSet<>(expiredIds);
        for (var attemptId : expiredIds) {
            runAttemptSafely(attemptId, () -> attemptService.expireIfDue(attemptId));
        }

        List<UUID> ungradedIds = nextUngradedBatch();
        for (var attemptId : ungradedIds) {
            if (processedIds.contains(attemptId)) continue;
            runAttemptSafely(attemptId, () -> attemptService.gradePendingSubmission(attemptId));
        }
    }

    private List<UUID> nextExpiredBatch(OffsetDateTime asOf) {
        List<UUID> ids = attemptRepository.findExpiredAttemptIdsAfter(asOf, expiredCursor, BATCH_SIZE);
        if (ids.isEmpty() && expiredCursor != null) {
            expiredCursor = null;
            ids = attemptRepository.findExpiredAttemptIdsAfter(asOf, null, BATCH_SIZE);
        }
        if (!ids.isEmpty()) expiredCursor = ids.getLast();
        return ids;
    }

    private List<UUID> nextUngradedBatch() {
        List<UUID> ids = attemptRepository.findSubmittedUngradedAttemptIdsAfter(ungradedCursor, BATCH_SIZE);
        if (ids.isEmpty() && ungradedCursor != null) {
            ungradedCursor = null;
            ids = attemptRepository.findSubmittedUngradedAttemptIdsAfter(null, BATCH_SIZE);
        }
        if (!ids.isEmpty()) ungradedCursor = ids.getLast();
        return ids;
    }

    private void runAttemptSafely(UUID attemptId, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException exception) {
            log.warn("Failed to process quiz attempt {}; it will be retried by a later scan",
                    attemptId, exception);
        }
    }
}
