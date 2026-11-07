package vn.edu.hcmut.co3103.lms.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.edu.hcmut.co3103.lms.entity.Chapter;

public record LessonRequest(
        @NotBlank String title,
        String description,
        @JsonDeserialize(using = StrictIntegerDeserializer.class) @Min(0) @Max(1440) Integer estimated_duration,
        @JsonDeserialize(using = StrictIntegerDeserializer.class) @Min(0) Integer sequence_order,
        Chapter.VisibilityStatus status
) { }
