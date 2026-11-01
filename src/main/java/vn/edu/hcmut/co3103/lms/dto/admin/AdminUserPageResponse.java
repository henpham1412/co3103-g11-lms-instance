package vn.edu.hcmut.co3103.lms.dto.admin;

import java.util.List;

public record AdminUserPageResponse(
        List<AdminUserResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last) {
}
