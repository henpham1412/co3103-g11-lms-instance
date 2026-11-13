package vn.edu.hcmut.co3103.lms.controller.adaptive;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.response.adaptive.RecommendedActivitiesResponse;
import vn.edu.hcmut.co3103.lms.dto.response.adaptive.TutorMasteryResponse;
import vn.edu.hcmut.co3103.lms.service.adaptive.AdaptiveLearningReadService;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AdaptiveLearningController {
    private final AdaptiveLearningReadService adaptive;

    @GetMapping("/classes/{classId}/recommended-activities")
    @PreAuthorize("hasRole('STUDENT')")
    public RecommendedActivitiesResponse recommendedActivities(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID classId) {
        return adaptive.recommendedActivities(classId, userId(jwt));
    }

    @GetMapping("/tutor/classes/{classId}/members/{classMemberId}/mastery")
    @PreAuthorize("hasRole('TUTOR')")
    public TutorMasteryResponse tutorMastery(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID classId,
                                           @PathVariable UUID classMemberId) {
        return adaptive.tutorMastery(classId, classMemberId, userId(jwt));
    }

    private UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }
}
