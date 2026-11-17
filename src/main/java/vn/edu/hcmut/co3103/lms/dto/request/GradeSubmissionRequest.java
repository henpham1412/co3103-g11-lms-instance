package vn.edu.hcmut.co3103.lms.dto.request;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record GradeSubmissionRequest(@NotNull BigDecimal score, String feedback, Long version) { }
