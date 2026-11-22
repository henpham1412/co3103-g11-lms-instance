package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * LMS-15 audit log response matching DOC-05 §5.5. {@code details} is always a structured JSON object:
 * valid stored JSON objects (such as {@code before}/{@code after} transitions) are preserved as-is,
 * while malformed or non-object legacy values are replaced with a neutral non-sensitive fallback.
 */
public record AuditLogResponse(
        @JsonProperty("audit_log_id") UUID auditLogId,
        @JsonProperty("actor_id") UUID actorId,
        String action,
        @JsonProperty("entity_type") String entityType,
        @JsonProperty("entity_id") UUID entityId,
        JsonNode details,
        @JsonProperty("created_at") Instant createdAt
) { }
