package vn.edu.hcmut.co3103.lms.repository;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.dto.admin.AdminUserQueryValidation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class LmsUserAccessSpecification {

    private LmsUserAccessSpecification() {
    }

    public static Specification<LmsUserAccess> filter(String q, LmsRole lmsRole, AccessStatus accessStatus) {
        AdminUserQueryValidation.requireValidSearchQuery(q);
        return (root, query, builder) -> {
            List<Predicate> filters = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String search = q.strip().toLowerCase(Locale.ROOT);
                String pattern = "%" + search.replace("\\", "\\\\")
                        .replace("%", "\\%").replace("_", "\\_") + "%";
                List<Predicate> matches = new ArrayList<>();
                matches.add(builder.like(builder.lower(root.get("fullName")), pattern, '\\'));
                matches.add(builder.like(builder.lower(root.get("email")), pattern, '\\'));
                try {
                    UUID userId = UUID.fromString(search);
                    // UUID.fromString also accepts shortened forms such as 1-1-1-1-1.
                    if (userId.toString().equals(search)) {
                        matches.add(builder.equal(root.get("userId"), userId));
                    }
                } catch (IllegalArgumentException ignored) {
                    // Non-UUID queries still search the name and email.
                }
                filters.add(builder.or(matches.toArray(Predicate[]::new)));
            }
            if (lmsRole != null) {
                filters.add(builder.equal(root.get("lmsRole"), lmsRole));
            }
            if (accessStatus != null) {
                filters.add(builder.equal(root.get("accessStatus"), accessStatus));
            }
            return builder.and(filters.toArray(Predicate[]::new));
        };
    }
}
