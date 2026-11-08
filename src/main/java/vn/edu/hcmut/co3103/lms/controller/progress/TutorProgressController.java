package vn.edu.hcmut.co3103.lms.controller.progress;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import vn.edu.hcmut.co3103.lms.dto.response.TutorClassProgressResponse;
import vn.edu.hcmut.co3103.lms.dto.response.TutorMemberProgressResponse;
import vn.edu.hcmut.co3103.lms.service.progress.TutorProgressService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tutor/classes")
public class TutorProgressController {
    private final TutorProgressService progressService;

    public TutorProgressController(TutorProgressService progressService) {
        this.progressService = progressService;
    }

    @GetMapping("/{classId}/progress")
    @PreAuthorize("hasRole('TUTOR')")
    public TutorClassProgressResponse getClassProgress(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId) {
        return progressService.getClassProgress(classId, userId(jwt));
    }

    @GetMapping("/{classId}/members/{classMemberId}/progress")
    @PreAuthorize("hasRole('TUTOR')")
    public TutorMemberProgressResponse getMemberProgress(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId, @PathVariable UUID classMemberId) {
        return progressService.getMemberProgress(classId, classMemberId, userId(jwt));
    }

    private UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }
}
