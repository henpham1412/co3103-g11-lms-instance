package vn.edu.hcmut.co3103.lms.controller.admin;

import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.dto.admin.AdminUserPageResponse;
import vn.edu.hcmut.co3103.lms.dto.admin.AdminUserResponse;
import vn.edu.hcmut.co3103.lms.dto.admin.UpdateUserAccessRequest;
import vn.edu.hcmut.co3103.lms.service.admin.AdminUserService;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<AdminUserPageResponse> getUsers(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "role", required = false) LmsRole role,
            @RequestParam(name = "status", required = false) AccessStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(service.getUsers(query, role, status, pageable));
    }

    @PatchMapping(value = "/{userId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminUserResponse> updateUserAccess(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserAccessRequest request,
            Authentication authentication,
            @RequestHeader HttpHeaders headers) throws HttpMediaTypeNotAcceptableException {
        requireAcceptableJsonResponse(headers);
        // LocalAccessJwtAuthenticationConverter uses the validated JWT subject as the principal name.
        UUID currentAdminUserId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(service.updateUserAccess(userId, request, currentAdminUserId));
    }

    private static void requireAcceptableJsonResponse(HttpHeaders headers)
            throws HttpMediaTypeNotAcceptableException {
        List<MediaType> accepted = headers.getAccept();
        // A specific JSON exclusion takes precedence over a positive wildcard range.
        boolean acceptsJson = accepted.isEmpty() || accepted.stream()
                .filter(type -> type.isCompatibleWith(MediaType.APPLICATION_JSON))
                .max(Comparator.<MediaType>comparingInt(type ->
                        type.isWildcardType() ? 0 : type.isWildcardSubtype() ? 1 : 2)
                        .thenComparingDouble(MediaType::getQualityValue))
                .map(type -> type.getQualityValue() > 0)
                .orElse(false);
        if (!acceptsJson) {
            throw new HttpMediaTypeNotAcceptableException(List.of(MediaType.APPLICATION_JSON));
        }
    }
}
