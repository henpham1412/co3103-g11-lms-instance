package vn.edu.hcmut.co3103.lms.controller.interaction;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.interaction.CreateDiscussionRequest;
import vn.edu.hcmut.co3103.lms.dto.request.interaction.CreateReplyRequest;
import vn.edu.hcmut.co3103.lms.dto.response.DiscussionResponse;
import vn.edu.hcmut.co3103.lms.dto.response.PageResponse;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.service.interaction.DiscussionService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DiscussionController {

    private static final int MAX_PAGE_SIZE = 100;

    private final DiscussionService discussionService;

    public DiscussionController(DiscussionService discussionService) {
        this.discussionService = discussionService;
    }

    @GetMapping("/classes/{classId}/discussions")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public PageResponse<DiscussionResponse> listThreads(@AuthenticationPrincipal Jwt jwt,
                                                        Authentication authentication,
                                                        @PathVariable UUID classId,
                                                        @RequestParam(name = "lesson_id", required = false) UUID lessonId,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        requirePagination(page, size);
        return discussionService.listThreads(classId, userId(jwt), isTutor(authentication), lessonId, page, size);
    }

    @PostMapping("/classes/{classId}/discussions")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public ResponseEntity<DiscussionResponse> createThread(@AuthenticationPrincipal Jwt jwt,
                                                           Authentication authentication,
                                                           @PathVariable UUID classId,
                                                           @Valid @RequestBody CreateDiscussionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(discussionService.createThread(
                classId, userId(jwt), isTutor(authentication), request));
    }

    @GetMapping("/discussions/{discussionId}")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public DiscussionResponse getDiscussion(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                            @PathVariable UUID discussionId) {
        return discussionService.getDiscussion(discussionId, userId(jwt), isTutor(authentication));
    }

    @PatchMapping("/discussions/{discussionId}")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public DiscussionResponse updateDiscussion(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                               @PathVariable UUID discussionId,
                                               @RequestBody Map<String, Object> patch) {
        return discussionService.updateDiscussion(discussionId, userId(jwt), isTutor(authentication), patch);
    }

    @DeleteMapping("/discussions/{discussionId}")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public ResponseEntity<Void> deleteDiscussion(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                                 @PathVariable UUID discussionId) {
        discussionService.deleteDiscussion(discussionId, userId(jwt), isTutor(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/discussions/{discussionId}/replies")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public ResponseEntity<DiscussionResponse> createReply(@AuthenticationPrincipal Jwt jwt,
                                                          Authentication authentication,
                                                          @PathVariable UUID discussionId,
                                                          @Valid @RequestBody CreateReplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(discussionService.createReply(
                discussionId, userId(jwt), isTutor(authentication), request));
    }

    private void requirePagination(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
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