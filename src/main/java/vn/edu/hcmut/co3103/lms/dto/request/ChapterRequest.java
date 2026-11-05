package vn.edu.hcmut.co3103.lms.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import vn.edu.hcmut.co3103.lms.entity.Chapter;

public record ChapterRequest(
        @NotBlank String title,
        String description,
        @Min(0) int sequence_order,
        Chapter.VisibilityStatus status
) { }
