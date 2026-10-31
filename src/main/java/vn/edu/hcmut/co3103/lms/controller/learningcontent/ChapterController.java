package vn.edu.hcmut.co3103.lms.controller.learningcontent;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.ChapterRequest;
import vn.edu.hcmut.co3103.lms.dto.request.LessonRequest;
import vn.edu.hcmut.co3103.lms.dto.request.MaterialRequest;
import vn.edu.hcmut.co3103.lms.dto.request.OrderUpdateRequest;
import vn.edu.hcmut.co3103.lms.dto.response.ClassOutlineResponse;
import vn.edu.hcmut.co3103.lms.dto.response.ContentItemResponse;
import vn.edu.hcmut.co3103.lms.entity.Chapter;
import vn.edu.hcmut.co3103.lms.entity.Lesson;
import vn.edu.hcmut.co3103.lms.entity.Material;
import vn.edu.hcmut.co3103.lms.service.learningcontent.ChapterService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ChapterController {
    private final ChapterService chapterService;

    public ChapterController(ChapterService chapterService) { this.chapterService = chapterService; }

    @GetMapping("/classes/{classId}/outline")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public ClassOutlineResponse outline(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                        @PathVariable UUID classId) {
        return chapterService.getOutline(classId, userId(jwt), isTutor(authentication));
    }

    @GetMapping("/classes/{classId}/chapters")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public List<ContentItemResponse.ChapterItem> listChapters(@AuthenticationPrincipal Jwt jwt,
                                                               Authentication authentication,
                                                               @PathVariable UUID classId) {
        return chapterService.listChapters(classId, userId(jwt), isTutor(authentication));
    }

    @PostMapping("/classes/{classId}/chapters")
    @PreAuthorize("hasRole('TUTOR')")
    public ResponseEntity<ContentItemResponse.ChapterItem> createChapter(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId, @Valid @RequestBody ChapterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ContentItemResponse.ChapterItem.from(chapterService.createChapter(classId, userId(jwt), request)));
    }

    @GetMapping("/chapters/{chapterId}")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public ContentItemResponse.ChapterItem getChapter(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                                       @PathVariable UUID chapterId) {
        return chapterService.getChapter(chapterId, userId(jwt), isTutor(authentication));
    }

    @PatchMapping("/chapters/{chapterId}")
    @PreAuthorize("hasRole('TUTOR')")
    public ContentItemResponse.ChapterItem updateChapter(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID chapterId, @RequestBody Map<String, Object> patch) {
        return ContentItemResponse.ChapterItem.from(chapterService.updateChapter(chapterId, userId(jwt), patch));
    }

    @DeleteMapping("/chapters/{chapterId}")
    @PreAuthorize("hasRole('TUTOR')")
    public ResponseEntity<Void> deleteChapter(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID chapterId) {
        chapterService.deleteChapter(chapterId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/classes/{classId}/chapter-order")
    @PreAuthorize("hasRole('TUTOR')")
    public ResponseEntity<Void> reorderChapters(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID classId,
                                                 @RequestBody OrderUpdateRequest request) {
        chapterService.reorderChapters(classId, userId(jwt), request.chapterIds());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/chapters/{chapterId}/lessons")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public List<ContentItemResponse.LessonItem> listLessons(@AuthenticationPrincipal Jwt jwt,
            Authentication authentication, @PathVariable UUID chapterId) {
        return chapterService.listLessons(chapterId, userId(jwt), isTutor(authentication));
    }

    @PostMapping("/chapters/{chapterId}/lessons")
    @PreAuthorize("hasRole('TUTOR')")
    public ResponseEntity<ContentItemResponse.LessonItem> createLesson(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID chapterId, @Valid @RequestBody LessonRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ContentItemResponse.LessonItem.from(chapterService.createLesson(chapterId, userId(jwt), request)));
    }

    @GetMapping("/lessons/{lessonId}")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public ContentItemResponse.LessonItem getLesson(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                                     @PathVariable UUID lessonId) {
        return chapterService.getLesson(lessonId, userId(jwt), isTutor(authentication));
    }

    @PatchMapping("/lessons/{lessonId}")
    @PreAuthorize("hasRole('TUTOR')")
    public ContentItemResponse.LessonItem updateLesson(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID lessonId, @RequestBody Map<String, Object> patch) {
        return ContentItemResponse.LessonItem.from(chapterService.updateLesson(lessonId, userId(jwt), patch));
    }

    @DeleteMapping("/lessons/{lessonId}")
    @PreAuthorize("hasRole('TUTOR')")
    public ResponseEntity<Void> deleteLesson(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID lessonId) {
        chapterService.deleteLesson(lessonId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/chapters/{chapterId}/lesson-order")
    @PreAuthorize("hasRole('TUTOR')")
    public ResponseEntity<Void> reorderLessons(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID chapterId,
                                                @RequestBody OrderUpdateRequest request) {
        chapterService.reorderLessons(chapterId, userId(jwt), request.lessonIds());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/lessons/{lessonId}/materials")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public List<ContentItemResponse.MaterialItem> listMaterials(@AuthenticationPrincipal Jwt jwt,
            Authentication authentication, @PathVariable UUID lessonId) {
        return chapterService.listMaterials(lessonId, userId(jwt), isTutor(authentication));
    }

    @PostMapping("/lessons/{lessonId}/materials")
    @PreAuthorize("hasRole('TUTOR')")
    public ResponseEntity<ContentItemResponse.MaterialItem> createMaterial(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID lessonId, @Valid @RequestBody MaterialRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ContentItemResponse.MaterialItem.from(chapterService.createMaterial(lessonId, userId(jwt), request)));
    }

    @GetMapping("/materials/{materialId}")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR')")
    public ContentItemResponse.MaterialItem getMaterial(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
                                                         @PathVariable UUID materialId) {
        return chapterService.getMaterial(materialId, userId(jwt), isTutor(authentication));
    }

    @PatchMapping("/materials/{materialId}")
    @PreAuthorize("hasRole('TUTOR')")
    public ContentItemResponse.MaterialItem updateMaterial(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID materialId, @RequestBody Map<String, Object> patch) {
        return ContentItemResponse.MaterialItem.from(chapterService.updateMaterial(materialId, userId(jwt), patch));
    }

    @DeleteMapping("/materials/{materialId}")
    @PreAuthorize("hasRole('TUTOR')")
    public ResponseEntity<Void> deleteMaterial(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID materialId) {
        chapterService.deleteMaterial(materialId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    private UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }

    private boolean isTutor(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(authority -> "ROLE_TUTOR".equals(authority.getAuthority()));
    }
}
