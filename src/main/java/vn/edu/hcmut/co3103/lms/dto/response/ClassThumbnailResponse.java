package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ClassThumbnailResponse(@JsonProperty("thumbnail_url") String thumbnailUrl) {
}
