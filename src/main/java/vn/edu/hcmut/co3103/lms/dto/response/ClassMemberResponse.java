package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;

import java.time.Instant;
import java.util.UUID;

public record ClassMemberResponse(
        @JsonProperty("class_member_id") UUID classMemberId,
        @JsonProperty("class_id") UUID classId,
        @JsonProperty("student_id") UUID studentId,
        String status,
        @JsonProperty("requested_at") Instant requestedAt,
        @JsonProperty("joined_at") Instant joinedAt
) {
    public static ClassMemberResponse from(ClassMember member) {
        return new ClassMemberResponse(
                member.getClassMemberId(),
                member.getClassId(),
                member.getStudentId(),
                member.getStatus().name(),
                member.getRequestedAt(),
                member.getJoinedAt()
        );
    }
}
