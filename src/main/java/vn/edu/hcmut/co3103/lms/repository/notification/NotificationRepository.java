package vn.edu.hcmut.co3103.lms.repository.notification;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.edu.hcmut.co3103.lms.dto.response.NotificationResponse;
import vn.edu.hcmut.co3103.lms.dto.response.PageResponse;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class NotificationRepository {
    static final String INSERT = """
            INSERT INTO notification (recipient_id, notification_type, title, message,
                                      target_url, source_event_key, created_at)
            """;
    static final String IGNORE_DUPLICATE = " ON CONFLICT ON CONSTRAINT uq_notification_recipient_event DO NOTHING";
    private static final String FIELDS = """
            notification_id, notification_type, title, message, target_url, is_read, created_at, read_at
            """;
    private static final RowMapper<NotificationResponse> RESPONSE = (row, rowNumber) -> {
        OffsetDateTime readAt = row.getObject("read_at", OffsetDateTime.class);
        return new NotificationResponse(row.getObject("notification_id", UUID.class),
                row.getString("notification_type"), row.getString("title"), row.getString("message"),
                row.getString("target_url"), row.getBoolean("is_read"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                readAt == null ? null : readAt.toInstant());
    };

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<NotificationResponse> list(UUID recipient, Boolean read, int page, int size, OffsetDateTime cutoff) {
        String filter = " FROM notification WHERE recipient_id = :recipient AND created_at > :cutoff";
        if (read != null) filter += read ? " AND is_read" : " AND NOT is_read";
        var args = Map.of("recipient", recipient, "cutoff", cutoff,
                "offset", (long) (page - 1) * size, "size", size);
        long count = jdbc.queryForObject("SELECT count(*)" + filter, args, Long.class);
        var rows = jdbc.query("SELECT " + FIELDS + filter
                + " ORDER BY created_at DESC, notification_id DESC LIMIT :size OFFSET :offset", args, RESPONSE);
        return new PageResponse<>(rows, page, size, count, (int) Math.ceil((double) count / size));
    }

    public Optional<UUID> lockRecipient(UUID id, OffsetDateTime cutoff) {
        return jdbc.query("""
                SELECT recipient_id FROM notification
                WHERE notification_id = :id AND created_at > :cutoff
                FOR UPDATE
                """, Map.of("id", id, "cutoff", cutoff), (row, index) -> row.getObject(1, UUID.class))
                .stream().findFirst();
    }

    public NotificationResponse markRead(UUID id, OffsetDateTime now) {
        return jdbc.queryForObject("""
                UPDATE notification
                SET is_read = TRUE, read_at = COALESCE(read_at, GREATEST(:now, created_at))
                WHERE notification_id = :id
                RETURNING
                """ + FIELDS, Map.of("id", id, "now", now), RESPONSE);
    }

    public int deleteExpired(OffsetDateTime cutoff) {
        return jdbc.update("DELETE FROM notification WHERE created_at <= :cutoff", Map.of("cutoff", cutoff));
    }
}
