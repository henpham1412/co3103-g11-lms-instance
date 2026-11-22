package vn.edu.hcmut.co3103.lms.service.admin;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.AuditLog;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.dto.response.AuditLogResponse;
import vn.edu.hcmut.co3103.lms.dto.response.PageResponse;
import vn.edu.hcmut.co3103.lms.entity.ContentReport;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.repository.AuditLogRepository;
import vn.edu.hcmut.co3103.lms.repository.AuditLogSpecification;

import java.time.Instant;
import java.util.UUID;

/** Read-only retrieval service for LMS-15 administrative audit logs (DOC-05 §5.5). */
@Service
@Transactional(readOnly = true)
public class AdminAuditLogService {

    public static final int MAX_PAGE_SIZE = 100;
    private static final String ACTION_USER_ACCESS_UPDATED = "LMS_USER_ACCESS_UPDATED";
    private static final String ACTION_CONTENT_REPORT_REVIEWED = "REPORTED_CONTENT_REVIEWED";
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("auditLogId"));

    private final AuditLogRepository auditLogs;
    private final ObjectMapper objectMapper;
    private final JsonNode unavailableDetails;

    public AdminAuditLogService(AuditLogRepository auditLogs, ObjectMapper objectMapper) {
        this.auditLogs = auditLogs;
        this.objectMapper = objectMapper;
        this.unavailableDetails = objectMapper.createObjectNode().put("unavailable", true);
    }

    public PageResponse<AuditLogResponse> list(UUID actorId, String action, String entityType,
                                               Instant from, Instant to, int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE || (long) (page - 1) * size > Integer.MAX_VALUE) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        Page<AuditLogResponse> result = auditLogs
                .findAll(AuditLogSpecification.filter(actorId, action, entityType, from, to),
                        PageRequest.of(page - 1, size, DEFAULT_SORT))
                .map(this::toResponse);
        return PageResponse.from(result);
    }

    public AuditLogResponse get(UUID logId) {
        AuditLog entry = auditLogs.findById(logId)
                .orElseThrow(() -> new AppException(ErrorCode.AUDIT_LOG_NOT_FOUND));
        return toResponse(entry);
    }

    private AuditLogResponse toResponse(AuditLog entry) {
        return new AuditLogResponse(
                entry.getAuditLogId(),
                entry.getActorId(),
                entry.getAction(),
                entry.getEntityType(),
                entry.getEntityId(),
                safeDetails(entry.getAction(), entry.getDetails()),
                entry.getCreatedAt()
        );
    }

    /**
     * Validates stored audit metadata against the closed schemas emitted by known writers without mutating
     * persisted rows. Any unknown action, malformed JSON, extra/missing field, or invalid enum value falls
     * back to a neutral non-sensitive object so unexpected or sensitive data can never be echoed.
     */
    private JsonNode safeDetails(String action, String raw) {
        if (action == null || raw == null || raw.isBlank()) {
            return unavailableDetails;
        }
        try {
            JsonNode parsed = objectMapper.readTree(raw);
            return isAllowedDetails(action, parsed) ? parsed : unavailableDetails;
        } catch (JacksonException malformed) {
            return unavailableDetails;
        }
    }

    private static boolean isAllowedDetails(String action, JsonNode root) {
        if (root == null || !root.isObject() || root.size() != 2) {
            return false;
        }
        JsonNode before = root.get("before");
        JsonNode after = root.get("after");
        if (before == null || !before.isObject() || after == null || !after.isObject()) {
            return false;
        }
        return switch (action) {
            case ACTION_USER_ACCESS_UPDATED ->
                    isValidUserAccessSnapshot(before) && isValidUserAccessSnapshot(after);
            case ACTION_CONTENT_REPORT_REVIEWED ->
                    isValidReportStatusSnapshot(before) && isValidReportStatusSnapshot(after);
            default -> false;
        };
    }

    private static boolean isValidUserAccessSnapshot(JsonNode node) {
        return node.size() == 2
                && hasEnumValue(node, "lms_role", LmsRole.class)
                && hasEnumValue(node, "access_status", AccessStatus.class);
    }

    private static boolean isValidReportStatusSnapshot(JsonNode node) {
        return node.size() == 1
                && hasEnumValue(node, "status", ContentReport.ReportStatus.class);
    }

    private static <E extends Enum<E>> boolean hasEnumValue(JsonNode node, String field, Class<E> enumType) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            return false;
        }
        try {
            Enum.valueOf(enumType, value.asString());
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
