package vn.edu.hcmut.co3103.lms.service.assessment;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static vn.edu.hcmut.co3103.lms.service.assessment.QuizAttemptEligibility.*;

class QuizAttemptEligibilityTest {
    final OffsetDateTime now = OffsetDateTime.parse("2026-11-13T12:00:00Z");
    final Window window = new Window(30, 2, now, now.plusHours(2));

    @Test void openingInstantIsInclusive() { assertThat(evaluate(window, List.of(), true, now)).isEqualTo(Action.START); }
    @Test void deadlineInstantIsExclusive() { assertThat(evaluate(window, List.of(), true, window.deadline())).isEqualTo(Action.NOT_AVAILABLE); }
    @Test void beforeOpeningIsUnavailable() { assertThat(evaluate(window, List.of(), true, now.minusNanos(1))).isEqualTo(Action.NOT_AVAILABLE); }
    @Test void emptyQuizCannotStart() { assertThat(evaluate(window, List.of(), false, now)).isEqualTo(Action.NOT_AVAILABLE); }
    @Test void allSubmittedAttemptsConsumeSlotsIndependentlyOfGrading() {
        assertThat(evaluate(window, List.of(new Attempt("SUBMITTED", now), new Attempt("SUBMITTED", now)), true, now)).isEqualTo(Action.ATTEMPT_LIMIT_REACHED);
    }
    @Test void oneRemainingSlotAllowsRetry() {
        assertThat(evaluate(window, List.of(new Attempt("SUBMITTED", now)), true, now)).isEqualTo(Action.START);
    }
    @Test void existingUnexpiredAttemptResumesBeforeCheckingNewStartWindowOrLimit() {
        var changedSettings = new Window(30, 1, now.plusMinutes(10), now.plusHours(2));
        assertThat(evaluate(changedSettings, List.of(new Attempt("IN_PROGRESS", now)), false, now)).isEqualTo(Action.RESUME);
    }
    @Test void expiredInProgressAttemptBlocksStartingAndIsNotSubmittedAsASideEffect() {
        assertThat(evaluate(window, List.of(new Attempt("IN_PROGRESS", now.minusMinutes(30))), true, now)).isEqualTo(Action.EXPIRED_IN_PROGRESS);
    }
    @Test void deadlineCanShortenTimeLimit() {
        assertThat(endAt(now, 30, now.plusMinutes(10))).isEqualTo(now.plusMinutes(10));
        assertThat(endAt(now, 30, now.plusHours(2))).isEqualTo(now.plusMinutes(30));
    }
    @Test void malformedConfigurationFailsClosed() {
        assertThat(evaluate(new Window(0, 2, null, now.plusHours(1)), List.of(), true, now)).isEqualTo(Action.NOT_AVAILABLE);
        assertThat(evaluate(new Window(30, 0, null, now.plusHours(1)), List.of(), true, now)).isEqualTo(Action.NOT_AVAILABLE);
        assertThat(evaluate(new Window(30, 2, null, null), List.of(), true, now)).isEqualTo(Action.NOT_AVAILABLE);
    }
}
