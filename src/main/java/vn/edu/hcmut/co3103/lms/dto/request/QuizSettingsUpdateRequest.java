package vn.edu.hcmut.co3103.lms.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
import lombok.Getter;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, setterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
@Getter
public class QuizSettingsUpdateRequest {
    private Integer timeLimitMinutes;
    private Integer attemptsAllowed;
    private String gradingMethod;
    private OffsetDateTime openingTime;
    private OffsetDateTime deadline;
    private BigDecimal requiredScore;
    private boolean timeLimitMinutesProvided, attemptsAllowedProvided, gradingMethodProvided;
    private boolean openingTimeProvided, deadlineProvided, requiredScoreProvided;

    // Custom setters to track which fields are provided in the request (for NULL vs. not provided distinction)
    @JsonDeserialize(using = AssessmentIntegerDeserializer.class) @JsonProperty("time_limit_minutes") public void setTimeLimitMinutes(Integer value) { timeLimitMinutesProvided = true; timeLimitMinutes = value; }
    @JsonDeserialize(using = AssessmentIntegerDeserializer.class) @JsonProperty("attempts_allowed") public void setAttemptsAllowed(Integer value) { attemptsAllowedProvided = true; attemptsAllowed = value; }
    @JsonProperty("grading_method") public void setGradingMethod(String value) { gradingMethodProvided = true; gradingMethod = value; }
    @JsonProperty("opening_time") public void setOpeningTime(OffsetDateTime value) { openingTimeProvided = true; openingTime = value; }
    @JsonProperty("deadline") public void setDeadline(OffsetDateTime value) { deadlineProvided = true; deadline = value; }
    @JsonProperty("required_score") public void setRequiredScore(BigDecimal value) { requiredScoreProvided = true; requiredScore = value; }

    @SuppressWarnings("unused") // hack for rejecting unknown fields in the request body
    @JsonAnySetter public void rejectUnknownField(String name, Object value) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

    public boolean hasAnyField() {
        return timeLimitMinutesProvided || attemptsAllowedProvided || gradingMethodProvided
                || openingTimeProvided || deadlineProvided || requiredScoreProvided;
    }
}