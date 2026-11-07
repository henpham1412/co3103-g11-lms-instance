package vn.edu.hcmut.co3103.lms.controller.progress;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import vn.edu.hcmut.co3103.lms.dto.request.LastVisitRequest;
import vn.edu.hcmut.co3103.lms.dto.response.LastVisitResponse;
import vn.edu.hcmut.co3103.lms.service.progress.LastVisitService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/classes")
public class LastVisitController {
    private final LastVisitService lastVisitService;

    public LastVisitController(LastVisitService lastVisitService) {
        this.lastVisitService = lastVisitService;
    }

    @PutMapping("/{classId}/last-visit")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<LastVisitResponse> recordVisit(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId,
            @Valid @RequestBody LastVisitRequest request
    ) {
        return ResponseEntity.ok(lastVisitService.recordVisit(classId, request.lessonId(), userId(jwt)));
    }

    private UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }
}
