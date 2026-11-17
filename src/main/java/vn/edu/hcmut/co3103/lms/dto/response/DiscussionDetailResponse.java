package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/** Existing post fields plus a bounded, flat descendant page; parent_id preserves nesting. */
public record DiscussionDetailResponse(
        @JsonUnwrapped DiscussionResponse discussion,
        PageResponse<DiscussionResponse> replies
) { }
