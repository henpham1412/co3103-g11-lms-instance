package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MembershipActionResponse(
        @JsonProperty("class_member_id") UUID classMemberId,
        @JsonProperty("class_id") UUID classId,
        String status,
        @JsonProperty("requested_at") Instant requestedAt,
        @JsonProperty("joined_at") Instant joinedAt
) {
    public static MembershipActionResponse from(ClassMember member) {
        return new MembershipActionResponse(
                member.getClassMemberId(),
                member.getClassId(),
                member.getStatus().name(),
                member.getRequestedAt(),
                member.getJoinedAt()
        );
    }
}
