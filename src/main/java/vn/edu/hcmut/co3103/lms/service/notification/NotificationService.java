package vn.edu.hcmut.co3103.lms.service.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.dto.response.NotificationResponse;
import vn.edu.hcmut.co3103.lms.dto.response.PageResponse;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.repository.notification.NotificationRepository;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class NotificationService {
    private static final int RETENTION_DAYS = 90;
    private final NotificationRepository repository;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<NotificationResponse> list(UUID recipient, Boolean read, int page, int size) {
        return repository.list(recipient, read, page, size, now().minusDays(RETENTION_DAYS));
    }

    @Transactional
    public NotificationResponse markRead(UUID recipient, UUID id) {
        OffsetDateTime now = now();
        UUID owner = repository.lockRecipient(id, now.minusDays(RETENTION_DAYS))
                .orElseThrow(() -> new AppException(ErrorCode.NOTIFICATION_NOT_FOUND));
        if (!owner.equals(recipient)) throw new AppException(ErrorCode.FORBIDDEN);
        return repository.markRead(id, now);
    }

    @Transactional
    public int deleteExpired() {
        return repository.deleteExpired(now().minusDays(RETENTION_DAYS));
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }
}
