package vn.edu.hcmut.co3103.lms.controller.moderation;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.moderation.CreateContentReportRequest;
import vn.edu.hcmut.co3103.lms.dto.response.ContentReportResponse;
import vn.edu.hcmut.co3103.lms.service.moderation.ContentReportService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ContentReportController {

    private final ContentReportService contentReportService;

    public ContentReportController(ContentReportService contentReportService) {
        this.contentReportService = contentReportService;
    }

    @PostMapping("/content-reports")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public ResponseEntity<ContentReportResponse> createReport(@AuthenticationPrincipal Jwt jwt,
                                                              Authentication authentication,
                                                              @Valid @RequestBody CreateContentReportRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(contentReportService.create(
                userId(jwt), isTutor(authentication), request));
    }

    private UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }

    private boolean isTutor(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_TUTOR".equals(authority.getAuthority()));
    }
}