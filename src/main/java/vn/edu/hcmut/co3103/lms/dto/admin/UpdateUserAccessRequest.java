package vn.edu.hcmut.co3103.lms.dto.admin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;

// Null fields preserve the user's current role or access status.
public record UpdateUserAccessRequest(LmsRole role, AccessStatus accessStatus) {

    @JsonCreator
    public static UpdateUserAccessRequest fromJson(@JsonProperty("role") Object role,
                                                   @JsonProperty("accessStatus") Object accessStatus) {
        return new UpdateUserAccessRequest(namedEnum(role, LmsRole.class), namedEnum(accessStatus, AccessStatus.class));
    }

    private static <E extends Enum<E>> E namedEnum(Object value, Class<E> enumType) {
        if (value == null) {
            return null;
        }
        // Inspect the original token type before Jackson can coerce an ordinal into an enum.
        if (!(value instanceof String name)) {
            throw new IllegalArgumentException("Access values must be named strings or null");
        }
        return Enum.valueOf(enumType, name);
    }
}
