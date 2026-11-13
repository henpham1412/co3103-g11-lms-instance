package vn.edu.hcmut.co3103.lms.controller.moderation;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.response.AdminContentReportResponse;
import vn.edu.hcmut.co3103.lms.dto.response.PageResponse;
import vn.edu.hcmut.co3103.lms.entity.ContentReport;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.service.moderation.ContentReportService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/content-reports")
public class AdminContentReportController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ContentReportService contentReportService;

    public AdminContentReportController(ContentReportService contentReportService) {
        this.contentReportService = contentReportService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public PageResponse<AdminContentReportResponse> listReports(
            @RequestParam(required = false) ContentReport.ReportStatus status,
            @RequestParam(name = "content_type", required = false) ContentReport.ContentType contentType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        return contentReportService.list(status, contentType, page, size);
    }

    @GetMapping("/{reportId}")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public AdminContentReportResponse getReport(@PathVariable UUID reportId) {
        return contentReportService.get(reportId);
    }

    @PatchMapping("/{reportId}")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public AdminContentReportResponse reviewReport(@AuthenticationPrincipal Jwt jwt,
                                                   @PathVariable UUID reportId,
                                                   @RequestBody Map<String, Object> patch) {
        return contentReportService.review(reportId, userId(jwt), patch);
    }

    private UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }
}