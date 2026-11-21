package vn.edu.hcmut.co3103.lms.controller.notification;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.co3103.lms.dto.response.NotificationResponse;
import vn.edu.hcmut.co3103.lms.dto.response.PageResponse;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.service.notification.NotificationService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<NotificationResponse> list(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam(name = "is_read", required = false) String read,
                                                    @RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        if (page < 1 || size < 1 || size > 100 || (read != null && !"true".equals(read) && !"false".equals(read))) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        return service.list(UUID.fromString(jwt.getSubject()), read == null ? null : Boolean.valueOf(read), page, size);
    }

    @PatchMapping("/{notificationId}")
    public NotificationResponse markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID notificationId,
                                         @RequestBody Map<String, Object> patch) {
        if (!Map.of("is_read", Boolean.TRUE).equals(patch)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        return service.markRead(UUID.fromString(jwt.getSubject()), notificationId);
    }
}
