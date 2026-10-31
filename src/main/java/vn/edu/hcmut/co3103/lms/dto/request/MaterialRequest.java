package vn.edu.hcmut.co3103.lms.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Material;

public record MaterialRequest(
        @NotBlank String title,
        @NotNull Material.MaterialType material_type,
        String file_url,
        String content,
        @Min(0) int sequence_order,
        Chapter.VisibilityStatus status
) { }
