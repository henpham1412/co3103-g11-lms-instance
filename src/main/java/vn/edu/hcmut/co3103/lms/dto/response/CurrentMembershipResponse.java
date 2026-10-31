package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;

import java.util.UUID;

public record CurrentMembershipResponse(
        @JsonProperty("class_id") UUID classId,
        String status
) {
    public static CurrentMembershipResponse from(ClassMember membership) {
        return new CurrentMembershipResponse(
                membership.getClassId(),
                membership.getStatus().name()
        );
    }
}
