package vn.edu.hcmut.co3103.lms.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        @JsonProperty("notification_id") UUID notificationId,
        @JsonProperty("notification_type") String notificationType,
        String title,
        String message,
        @JsonProperty("target_url") String targetUrl,
        @JsonProperty("is_read") boolean read,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("read_at") Instant readAt
) { }
