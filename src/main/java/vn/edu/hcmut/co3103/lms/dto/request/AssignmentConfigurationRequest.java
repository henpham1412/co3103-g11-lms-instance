package vn.edu.hcmut.co3103.lms.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;

public record AssignmentConfigurationRequest(
        @JsonProperty("opening_time") OffsetDateTime openingTime,
        @NotNull OffsetDateTime deadline,
        @JsonProperty("allow_late") Boolean allowLate,
        @JsonProperty("allowed_file_types") List<String> allowedFileTypes,
        @JsonProperty("max_file_size_mb") Integer maxFileSizeMb
) { }
