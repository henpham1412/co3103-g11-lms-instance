package vn.edu.hcmut.co3103.lms.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record LastVisitRequest(
        @NotNull @JsonProperty("lesson_id") UUID lessonId
) { }
