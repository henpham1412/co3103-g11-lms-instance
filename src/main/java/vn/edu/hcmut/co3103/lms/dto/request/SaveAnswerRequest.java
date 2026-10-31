package vn.edu.hcmut.co3103.lms.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record SaveAnswerRequest(
        @JsonProperty("selected_option_ids") List<UUID> selectedOptionIds
) {}
