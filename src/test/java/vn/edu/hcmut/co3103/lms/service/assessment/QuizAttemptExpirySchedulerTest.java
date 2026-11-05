package vn.edu.hcmut.co3103.lms.service.assessment;

import org.junit.jupiter.api.Test;
import vn.edu.hcmut.co3103.lms.repository.assessment.QuizAttemptRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuizAttemptExpirySchedulerTest {
    @Test
    void continuesBatchAndProcessesUngradedSubmissionsAfterAnAttemptFails() {
        QuizAttemptRepository attemptRepository = mock(QuizAttemptRepository.class);
        QuizAttemptService attemptService = mock(QuizAttemptService.class);
        QuizAttemptExpiryScheduler scheduler = new QuizAttemptExpiryScheduler(attemptRepository, attemptService);
        UUID failingExpiredId = UUID.randomUUID();
        UUID nextExpiredId = UUID.randomUUID();
        UUID ungradedSubmittedId = UUID.randomUUID();

        when(attemptRepository.findExpiredAttemptIdsAfter(any(OffsetDateTime.class), nullable(UUID.class), anyInt()))
                .thenReturn(List.of(failingExpiredId, nextExpiredId));
        when(attemptRepository.findSubmittedUngradedAttemptIdsAfter(nullable(UUID.class), anyInt()))
                .thenReturn(List.of(ungradedSubmittedId));
        doThrow(new IllegalStateException("temporary grading failure"))
                .when(attemptService).expireIfDue(failingExpiredId);

        scheduler.submitExpiredAttempts();

        verify(attemptService).expireIfDue(failingExpiredId);
        verify(attemptService).expireIfDue(nextExpiredId);
        verify(attemptService).gradePendingSubmission(ungradedSubmittedId);
    }

    @Test
    void advancesTheExpiredCursorSoRepeatedFailuresDoNotPinTheFirstPage() {
        QuizAttemptRepository attemptRepository = mock(QuizAttemptRepository.class);
        QuizAttemptService attemptService = mock(QuizAttemptService.class);
        QuizAttemptExpiryScheduler scheduler = new QuizAttemptExpiryScheduler(attemptRepository, attemptService);
        UUID failedFirstPageId = UUID.randomUUID();
        UUID laterPageId = UUID.randomUUID();

        when(attemptRepository.findExpiredAttemptIdsAfter(any(OffsetDateTime.class), nullable(UUID.class), anyInt()))
                .thenReturn(List.of(failedFirstPageId), List.of(laterPageId));
        when(attemptRepository.findSubmittedUngradedAttemptIdsAfter(nullable(UUID.class), anyInt()))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("still failing"))
                .when(attemptService).expireIfDue(failedFirstPageId);

        scheduler.submitExpiredAttempts();
        scheduler.submitExpiredAttempts();

        verify(attemptRepository).findExpiredAttemptIdsAfter(
                any(OffsetDateTime.class), eq(failedFirstPageId), anyInt());
        verify(attemptService).expireIfDue(laterPageId);
    }
}
