package vn.edu.hcmut.co3103.lms.controller.assessment;

import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ContentDisposition;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.AssignmentConfigurationRequest;
import vn.edu.hcmut.co3103.lms.dto.request.GradeSubmissionRequest;
import vn.edu.hcmut.co3103.lms.dto.response.AssignmentConfigurationResponse;
import vn.edu.hcmut.co3103.lms.dto.response.SubmissionResponse;
import vn.edu.hcmut.co3103.lms.entity.Submission;
import vn.edu.hcmut.co3103.lms.service.assessment.AssignmentService;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AssignmentController {
    private final AssignmentService assignmentService;

    public AssignmentController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @PostMapping("/activities/{activityId}/assignment")
    @PreAuthorize("hasRole('TUTOR')")
    public ResponseEntity<AssignmentConfigurationResponse> createAssignment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID activityId,
            @Valid @RequestBody AssignmentConfigurationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(assignmentService.createOrUpdate(activityId, userId(jwt), request));
    }

    @PutMapping("/activities/{activityId}/assignment")
    @PreAuthorize("hasRole('TUTOR')")
    public AssignmentConfigurationResponse updateAssignment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID activityId,
            @Valid @RequestBody AssignmentConfigurationRequest request) {
        return assignmentService.createOrUpdate(activityId, userId(jwt), request);
    }

    @GetMapping("/activities/{activityId}/assignment")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public AssignmentConfigurationResponse getAssignment(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable UUID activityId) {
        boolean tutor = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_TUTOR".equals(authority.getAuthority()));
        return assignmentService.getConfiguration(activityId, userId(jwt), tutor);
    }

    @PostMapping(path = "/assignments/{assignmentId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('TUTOR')")
    public AssignmentConfigurationResponse addAttachment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID assignmentId,
            @RequestPart("file") MultipartFile file) {
        return assignmentService.addAttachment(assignmentId, userId(jwt), file);
    }

    @PutMapping(path = "/assignments/{assignmentId}/submission",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<SubmissionResponse> submit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID assignmentId,
            @RequestPart("file") MultipartFile file) {
        UUID studentId = userId(jwt);
        boolean replacing = assignmentService.hasSubmission(assignmentId, studentId);
        SubmissionResponse response = assignmentService.submit(assignmentId, studentId, file);
        return ResponseEntity.status(replacing ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }

    @GetMapping("/assignments/{assignmentId}/submissions/me")
    @PreAuthorize("hasRole('STUDENT')")
    public SubmissionResponse getMine(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID assignmentId) {
        return assignmentService.getMine(assignmentId, userId(jwt));
    }

    @GetMapping("/assignments/{assignmentId}/attachments/{attachmentId}/file")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public ResponseEntity<Resource> downloadAttachment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID assignmentId,
            @PathVariable UUID attachmentId) {
        var file = assignmentService.downloadAttachment(assignmentId, attachmentId, userId(jwt));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header("Content-Disposition", ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file.path()));
    }

    @GetMapping("/assignments/{assignmentId}/submissions")
    @PreAuthorize("hasRole('TUTOR')")
    public org.springframework.data.domain.Page<SubmissionResponse> list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID assignmentId,
            @RequestParam(required = false) Submission.Status status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUERY_PARAMETER");
        }
        return assignmentService.list(assignmentId, userId(jwt), status,
                PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "submittedAt")));
    }

    @GetMapping("/submissions/{submissionId}")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public SubmissionResponse getSubmission(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID submissionId) {
        return assignmentService.getSubmission(submissionId, userId(jwt));
    }

    @GetMapping("/submissions/{submissionId}/file")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public ResponseEntity<Resource> downloadSubmission(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID submissionId) {
        var file = assignmentService.download(submissionId, userId(jwt));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header("Content-Disposition", ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(file.path()));
    }

    @PatchMapping("/submissions/{submissionId}/grade")
    @PreAuthorize("hasRole('TUTOR')")
    public SubmissionResponse grade(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID submissionId,
            @Valid @RequestBody GradeSubmissionRequest request) {
        return assignmentService.grade(submissionId, userId(jwt), request);
    }

    private UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }
}
