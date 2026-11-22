package vn.edu.hcmut.co3103.lms.controller.admin;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.co3103.lms.dto.response.AdminReportResponse;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.service.admin.AdminReportService;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * LMS-14 basic operational reports (DOC-05 §5.4). The only supported filter is an optional {@code class_id};
 * date ranges and any other query parameter are rejected rather than silently ignored.
 */
@RestController
@RequestMapping(value = "/api/v1/admin/reports", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasRole('ADMINISTRATOR')")
public class AdminReportController {

    private static final String CLASS_ID = "class_id";
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final AdminReportService service;

    public AdminReportController(AdminReportService service) {
        this.service = service;
    }

    @GetMapping("/enrollment")
    public AdminReportResponse<AdminReportResponse.Enrollment> enrollment(
            @RequestParam MultiValueMap<String, String> parameters) {
        return service.enrollment(classId(parameters));
    }

    @GetMapping("/completion")
    public AdminReportResponse<AdminReportResponse.Completion> completion(
            @RequestParam MultiValueMap<String, String> parameters) {
        return service.completion(classId(parameters));
    }

    @GetMapping("/assessment")
    public AdminReportResponse<AdminReportResponse.Assessment> assessment(
            @RequestParam MultiValueMap<String, String> parameters) {
        return service.assessment(classId(parameters));
    }

    private static UUID classId(MultiValueMap<String, String> parameters) {
        if (parameters.keySet().stream().anyMatch(name -> !CLASS_ID.equals(name))) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        List<String> values = parameters.get(CLASS_ID);
        if (values == null) {
            return null;
        }
        if (values.size() != 1 || values.getFirst() == null || !CANONICAL_UUID.matcher(values.getFirst()).matches()) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        return UUID.fromString(values.getFirst());
    }
}
