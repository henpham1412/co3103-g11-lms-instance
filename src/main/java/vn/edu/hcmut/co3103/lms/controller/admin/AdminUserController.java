package vn.edu.hcmut.co3103.lms.controller.admin;

import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
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

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping(value = {"/api/v1/admin/users", "/api/admin/users"}, produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class AdminUserController {

    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);
    private static final Map<String, String> SORT_PROPERTIES = Map.of(
            "user_id", "userId", "full_name", "fullName", "email", "email", "lms_role", "lmsRole",
            "access_status", "accessStatus", "last_login_at", "lastLoginAt",
            "created_at", "createdAt", "updated_at", "updatedAt");

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<AdminUserPageResponse> getUsers(
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestHeader HttpHeaders headers) throws HttpMediaTypeNotAcceptableException {
        requireAcceptableJsonResponse(headers);
        var role = UpdateUserAccessRequest.namedEnum(aliasedParameter(parameters, "lms_role", "role"), LmsRole.class);
        var status = UpdateUserAccessRequest.namedEnum(
                aliasedParameter(parameters, "access_status", "status"), AccessStatus.class);
        return ResponseEntity.ok().contentType(JSON_UTF8)
                .body(service.getUsers(singleParameter(parameters, "q"), role, status, pageable(parameters)));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<AdminUserResponse> getUser(@PathVariable UUID userId,
            @RequestHeader HttpHeaders headers) throws HttpMediaTypeNotAcceptableException {
        requireAcceptableJsonResponse(headers);
        return ResponseEntity.ok().contentType(JSON_UTF8).body(service.getUser(userId));
    }

    @PatchMapping("/{userId}")
    public ResponseEntity<AdminUserResponse> updateUserAccess(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateUserAccessRequest request,
            Authentication authentication,
            @RequestHeader HttpHeaders headers) throws HttpMediaTypeNotAcceptableException {
        requireAcceptableJsonResponse(headers);
        // LocalAccessJwtAuthenticationConverter uses the validated JWT subject as the principal name.
        UUID currentAdminUserId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok().contentType(JSON_UTF8)
                .body(service.updateUserAccess(userId, request, currentAdminUserId));
    }

    private static Pageable pageable(MultiValueMap<String, String> parameters) {
        int page = positiveInteger(singleParameter(parameters, "page"), 1);
        int size = Math.min(positiveInteger(singleParameter(parameters, "size"), 20), 2000);
        if ((long) (page - 1) * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Page offset exceeds the supported maximum");
        }
        List<String> sorts = parameters.getOrDefault("sort", List.of("created_at,desc"));
        List<Sort.Order> orders = sorts.stream().map(value -> {
            String[] parts = value.split(",", -1);
            if (parts.length > 2 || parts[0].isBlank()) {
                throw new IllegalArgumentException("Invalid sort");
            }
            String property = SORT_PROPERTIES.getOrDefault(parts[0], parts[0]);
            if (!SORT_PROPERTIES.containsValue(property)) {
                throw new IllegalArgumentException("Unsupported sort property");
            }
            return new Sort.Order(parts.length == 1 ? Sort.Direction.ASC : Sort.Direction.fromString(parts[1]), property);
        }).toList();
        return PageRequest.of(page - 1, size, Sort.by(orders));
    }

    private static int positiveInteger(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (!value.matches("[0-9]+")) {
            throw new IllegalArgumentException("Expected a positive integer");
        }
        int parsed = Integer.parseInt(value);
        if (parsed < 1) {
            throw new IllegalArgumentException("Expected a positive integer");
        }
        return parsed;
    }

    private static String singleParameter(MultiValueMap<String, String> parameters, String name) {
        List<String> values = parameters.get(name);
        if (values != null && values.size() != 1) {
            throw new IllegalArgumentException("Repeated parameter");
        }
        return values == null ? null : values.getFirst();
    }

    private static String aliasedParameter(MultiValueMap<String, String> parameters, String name, String alias) {
        if (parameters.containsKey(name) && parameters.containsKey(alias)) {
            throw new IllegalArgumentException("Ambiguous filter");
        }
        return singleParameter(parameters, parameters.containsKey(name) ? name : alias);
    }

    private static void requireAcceptableJsonResponse(HttpHeaders headers)
            throws HttpMediaTypeNotAcceptableException {
        boolean acceptsJson;
        try {
            List<MediaType> accepted = headers.getAccept();
            // Match the actual writable UTF-8 representation, including its parameters.
            // More specific ranges override wildcards, even when their quality is zero.
            acceptsJson = accepted.isEmpty() || accepted.stream()
                    .filter(type -> type.includes(MediaType.APPLICATION_JSON))
                    .filter(type -> type.getParameters().keySet().stream()
                            .allMatch(name -> name.equalsIgnoreCase("q") || name.equalsIgnoreCase("charset")))
                    .filter(type -> type.getCharset() == null || StandardCharsets.UTF_8.equals(type.getCharset()))
                    .max(Comparator.<MediaType>comparingInt(type ->
                            type.isWildcardType() ? 0 : type.isWildcardSubtype() ? 1 : 2)
                            .thenComparingInt(type -> type.getCharset() == null ? 0 : 1)
                            .thenComparingDouble(MediaType::getQualityValue))
                    .map(type -> type.getQualityValue() > 0)
                    .orElse(false);
        } catch (IllegalArgumentException invalidAccept) {
            acceptsJson = false;
        }
        if (!acceptsJson) {
            throw new HttpMediaTypeNotAcceptableException(List.of(JSON_UTF8));
        }
    }
}
