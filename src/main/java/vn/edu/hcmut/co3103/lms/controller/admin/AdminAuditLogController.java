package vn.edu.hcmut.co3103.lms.controller.admin;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.co3103.lms.dto.response.AuditLogResponse;
import vn.edu.hcmut.co3103.lms.dto.response.PageResponse;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.service.admin.AdminAuditLogService;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * LMS-15 read-only administrative audit log retrieval (DOC-05 §5.5).
 * No write or delete endpoints are exposed.
 */
@RestController
@RequestMapping(value = "/api/v1/admin/audit-logs", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class AdminAuditLogController {

    private static final Set<String> ALLOWED_PARAMS =
            Set.of("actor_id", "action", "entity_type", "from", "to", "page", "size");
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int MAX_FILTER_LENGTH = 100;

    private final AdminAuditLogService service;

    public AdminAuditLogController(AdminAuditLogService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<AuditLogResponse> list(@RequestParam MultiValueMap<String, String> parameters) {
        if (!ALLOWED_PARAMS.containsAll(parameters.keySet())) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        UUID actorId = optionalUuid(singleParam(parameters, "actor_id"));
        String action = optionalToken(singleParam(parameters, "action"));
        String entityType = optionalToken(singleParam(parameters, "entity_type"));
        Instant from = optionalInstant(singleParam(parameters, "from"));
        Instant to = optionalInstant(singleParam(parameters, "to"));
        int page = optionalPositiveInt(singleParam(parameters, "page"), 1);
        int size = optionalPositiveInt(singleParam(parameters, "size"), 20);
        return service.list(actorId, action, entityType, from, to, page, size);
    }

    @GetMapping("/{logId}")
    public AuditLogResponse get(@PathVariable String logId) {
        if (logId == null || !CANONICAL_UUID.matcher(logId).matches()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        return service.get(UUID.fromString(logId));
    }

    private static String singleParam(MultiValueMap<String, String> parameters, String name) {
        List<String> values = parameters.get(name);
        if (values == null) {
            return null;
        }
        if (values.size() != 1 || values.getFirst() == null) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        return values.getFirst();
    }

    private static UUID optionalUuid(String value) {
        if (value == null) {
            return null;
        }
        if (!CANONICAL_UUID.matcher(value).matches()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        return UUID.fromString(value);
    }

    private static String optionalToken(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_FILTER_LENGTH) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        return trimmed;
    }

    private static Instant optionalInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException invalid) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
    }

    private static int optionalPositiveInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (!value.matches("[0-9]+")) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new AppException(ErrorCode.VALIDATION_ERROR);
            }
            return parsed;
        } catch (NumberFormatException overflow) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
