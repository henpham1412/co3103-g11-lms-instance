package vn.edu.hcmut.co3103.lms.controller.progress;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.response.AssessmentHistoryItem;
import vn.edu.hcmut.co3103.lms.dto.response.PageResponse;
import vn.edu.hcmut.co3103.lms.service.progress.AssessmentHistoryService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me")
public class AssessmentHistoryController {
    private final AssessmentHistoryService historyService;

    public AssessmentHistoryController(AssessmentHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/assessment-history")
    @PreAuthorize("hasRole('STUDENT')")
    public PageResponse<AssessmentHistoryItem> getHistory(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "class_id", required = false) UUID classId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return historyService.getHistory(userId(jwt), classId, type, page, size);
    }

    private UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }
}
