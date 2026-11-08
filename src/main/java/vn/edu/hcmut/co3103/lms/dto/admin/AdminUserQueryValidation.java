package vn.edu.hcmut.co3103.lms.dto.admin;

import vn.edu.hcmut.co3103.lms.exception.AdminAccessValidationException;

public final class AdminUserQueryValidation {

    private AdminUserQueryValidation() {
    }

    public static void requireValidSearchQuery(String query) {
        // PostgreSQL text cannot represent NUL, even in a bound LIKE parameter.
        if (query != null && query.indexOf('\0') >= 0) {
            throw new AdminAccessValidationException();
        }
    }
}
