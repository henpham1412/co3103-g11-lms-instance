package vn.edu.hcmut.co3103.lms.service.assessment;

import java.time.OffsetDateTime;
import java.util.List;

/** Canonical, side-effect-free start/resume decision shared with read-only consumers. */
public final class QuizAttemptEligibility {
    private QuizAttemptEligibility() { }

    public enum Action { START, RESUME, NOT_AVAILABLE, EXPIRED_IN_PROGRESS, ATTEMPT_LIMIT_REACHED }
    public record Window(Integer timeLimitMinutes, Integer attemptsAllowed,
                         OffsetDateTime openingTime, OffsetDateTime deadline) { }
    /** Supply attempts in canonical attempt-number order. Every attempt consumes a slot. */
    public record Attempt(String status, OffsetDateTime startedAt) { }

    public static Action evaluate(Window quiz, List<Attempt> attempts, boolean hasQuestions, OffsetDateTime now) {
        for (Attempt attempt : attempts) {
            if (!"IN_PROGRESS".equals(attempt.status())) continue;
            if (attempt.startedAt() == null || quiz.timeLimitMinutes() == null || quiz.deadline() == null)
                return Action.NOT_AVAILABLE;
            return !now.isBefore(endAt(attempt.startedAt(), quiz.timeLimitMinutes(), quiz.deadline()))
                    ? Action.EXPIRED_IN_PROGRESS : Action.RESUME;
        }
        if (quiz.timeLimitMinutes() == null || quiz.timeLimitMinutes() <= 0
                || quiz.attemptsAllowed() == null || quiz.attemptsAllowed() < 1 || quiz.deadline() == null
                || (quiz.openingTime() != null && now.isBefore(quiz.openingTime()))
                || !now.isBefore(quiz.deadline()) || !hasQuestions) return Action.NOT_AVAILABLE;
        return attempts.size() >= quiz.attemptsAllowed() ? Action.ATTEMPT_LIMIT_REACHED : Action.START;
    }

    public static OffsetDateTime endAt(OffsetDateTime startedAt, int minutes, OffsetDateTime deadline) {
        OffsetDateTime timeLimitEnd = startedAt.plusMinutes(minutes);
        return timeLimitEnd.isBefore(deadline) ? timeLimitEnd : deadline;
    }
}
