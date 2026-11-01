package vn.edu.hcmut.co3103.lms.dto.admin;

import com.fasterxml.jackson.annotation.JsonCreator;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.exception.AdminAccessValidationException;

import java.util.Map;
import java.util.Set;

// Null fields preserve current access. Identity/display fields are never writable here.
public record UpdateUserAccessRequest(LmsRole role, AccessStatus accessStatus) {

    private static final Set<String> FIELDS = Set.of("lms_role", "access_status", "role", "accessStatus");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static UpdateUserAccessRequest fromJson(Map<String, Object> fields) {
        if (!FIELDS.containsAll(fields.keySet())) {
            throw new AdminAccessValidationException();
        }
        return new UpdateUserAccessRequest(
                namedEnum(aliasedValue(fields, "lms_role", "role"), LmsRole.class),
                namedEnum(aliasedValue(fields, "access_status", "accessStatus"), AccessStatus.class));
    }

    private static Object aliasedValue(Map<String, Object> fields, String name, String alias) {
        // Reject ambiguous requests rather than letting field order choose the update.
        if (fields.containsKey(name) && fields.containsKey(alias)) {
            throw new AdminAccessValidationException();
        }
        return fields.containsKey(name) ? fields.get(name) : fields.get(alias);
    }

    public static <E extends Enum<E>> E namedEnum(Object value, Class<E> enumType) {
        if (value == null) {
            return null;
        }
        if (value instanceof String name) {
            try {
                return Enum.valueOf(enumType, name);
            } catch (IllegalArgumentException ignored) {
                // Do not expose enum implementation names or client-supplied values.
            }
        }
        throw new AdminAccessValidationException();
    }
}
