package vn.edu.hcmut.co3103.lms.controller.progress;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.response.CompletionMarkResponse;
import vn.edu.hcmut.co3103.lms.service.progress.LessonCompletionService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/classes")
public class LessonCompletionController {
    private final LessonCompletionService completionService;

    public LessonCompletionController(LessonCompletionService completionService) {
        this.completionService = completionService;
    }

    @PutMapping("/{classId}/lessons/{lessonId}/completion")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<CompletionMarkResponse> markComplete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId,
            @PathVariable UUID lessonId
    ) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(completionService.markComplete(classId, lessonId, userId(jwt)));
    }

    @DeleteMapping("/{classId}/lessons/{lessonId}/completion")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<CompletionMarkResponse> markIncomplete(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId,
            @PathVariable UUID lessonId
    ) {
        return ResponseEntity.ok(completionService.markIncomplete(classId, lessonId, userId(jwt)));
    }

    private UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }
}
