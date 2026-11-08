package vn.edu.hcmut.co3103.lms.controller.classmanagement;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.ClassCreationRequest;
import vn.edu.hcmut.co3103.lms.dto.request.ClassUpdateRequest;
import vn.edu.hcmut.co3103.lms.dto.response.ClassCatalogResponse;
import vn.edu.hcmut.co3103.lms.dto.response.ClassCreationResponse;
import vn.edu.hcmut.co3103.lms.dto.response.ClassDetailResponse;
import vn.edu.hcmut.co3103.lms.dto.response.ClassMemberResponse;
import vn.edu.hcmut.co3103.lms.dto.response.ClassThumbnailResponse;
import vn.edu.hcmut.co3103.lms.dto.response.MembershipActionResponse;
import vn.edu.hcmut.co3103.lms.dto.response.PageResponse;
import vn.edu.hcmut.co3103.lms.dto.response.StudentClassPageResponse;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.mapper.ClassMapper;
import vn.edu.hcmut.co3103.lms.service.classmanagement.ClassMembershipService;
import vn.edu.hcmut.co3103.lms.service.classmanagement.ClassService;
import vn.edu.hcmut.co3103.lms.service.classmanagement.ClassThumbnailService;

import java.util.Map;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ClassController {

    private static final Map<String, String> CLASS_SORT_FIELDS = Map.of(
            "created_at", "createdAt",
            "updated_at", "updatedAt",
            "title", "title",
            "class_code", "classCode"
    );
    private static final Map<String, String> CATALOG_SORT_FIELDS = Map.of(
            "created_at", "created_at",
            "updated_at", "updated_at",
            "title", "title",
            "class_code", "class_code"
    );
    private static final Map<String, String> MEMBER_SORT_FIELDS = Map.of(
            "requested_at", "requested_at",
            "joined_at", "joined_at",
            "status", "status"
    );

    private final ClassService classService;
    private final ClassMembershipService membershipService;
    private final ClassMapper classMapper;
    private final ClassThumbnailService thumbnailService;

    public ClassController(
            ClassService classService,
            ClassMembershipService membershipService,
            ClassMapper classMapper,
            ClassThumbnailService thumbnailService
    ) {
        this.classService = classService;
        this.membershipService = membershipService;
        this.classMapper = classMapper;
        this.thumbnailService = thumbnailService;
    }

    @GetMapping("/classes")
    @PreAuthorize("hasRole('STUDENT')")
    public ClassCatalogResponse browseClasses(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "enrollment_policy", required = false)
            Class.EnrollmentPolicy enrollmentPolicy,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(defaultValue = "created_at,desc") String sort
    ) {
        return classService.browseClasses(
                query,
                enrollmentPolicy,
                pageable(page, size, sort, CATALOG_SORT_FIELDS)
        );
    }

    @GetMapping("/classes/{classId}")
    @PreAuthorize("hasAnyRole('STUDENT', 'TUTOR', 'ADMINISTRATOR')")
    public ClassDetailResponse getClassDetails(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication,
            @PathVariable UUID classId
    ) {
        boolean administrator = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMINISTRATOR".equals(authority.getAuthority()));
        return classService.getClassDetails(classId, userId(jwt), administrator);
    }

    @GetMapping("/me/classes")
    @PreAuthorize("hasRole('STUDENT')")
    public StudentClassPageResponse listCurrentStudentMemberships(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "status", required = false) Class.ClassStatus classStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 1 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUERY_PARAMETER");
        }
        Page<StudentClassPageResponse.Item> result = membershipService.listStudentClasses(
                userId(jwt), classStatus, PageRequest.of(page - 1, size)
        );
        return new StudentClassPageResponse(
                result.getContent(), result.getNumber() + 1, result.getSize(),
                result.getTotalElements(), result.getTotalPages()
        );
    }

    @GetMapping("/tutor/classes")
    @PreAuthorize("hasRole('TUTOR')")
    public PageResponse<ClassCreationResponse> listTutorClasses(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "created_at,desc") String sort
    ) {
        Page<ClassCreationResponse> result = classService.listTutorClasses(
                userId(jwt), pageable(page, size, sort, CLASS_SORT_FIELDS)
        );
        return PageResponse.from(result);
    }

    @PostMapping("/classes")
    @PreAuthorize("hasRole('TUTOR')")
    public ResponseEntity<ClassCreationResponse> createClass(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ClassCreationRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(classService.createClass(request, userId(jwt)));
    }

    @PatchMapping("/classes/{classId}")
    @PreAuthorize("hasRole('TUTOR')")
    public ClassCreationResponse updateClass(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId,
            @RequestBody ClassUpdateRequest request
    ) {
        return classMapper.toCreationResponse(
                classService.updateClass(classId, userId(jwt), request)
        );
    }

    @PostMapping(path = "/classes/{classId}/thumbnail", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('TUTOR')")
    public ClassThumbnailResponse uploadClassThumbnail(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId,
            @RequestPart("file") MultipartFile file
    ) {
        return new ClassThumbnailResponse(thumbnailService.upload(classId, userId(jwt), file));
    }

    @GetMapping("/classes/{classId}/members")
    @PreAuthorize("hasRole('TUTOR')")
    public PageResponse<ClassMemberResponse> listMembers(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId,
            @RequestParam(required = false) ClassMember.MembershipStatus status,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "requested_at,desc") String sort
    ) {
        Page<ClassMemberResponse> result = membershipService.listMembers(
                classId,
                userId(jwt),
                query,
                status,
                pageable(page, size, sort, MEMBER_SORT_FIELDS)
        ).map(ClassMemberResponse::from);
        return PageResponse.from(result);
    }

    @PostMapping("/classes/{classId}/membership")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<MembershipActionResponse> joinOpenClass(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId
    ) {
        ClassMembershipService.MembershipResult result = membershipService
                .joinOpenClass(classId, userId(jwt));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(MembershipActionResponse.from(result.membership()));
    }

    @PostMapping("/classes/{classId}/join-requests")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<MembershipActionResponse> requestToJoin(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId
    ) {
        ClassMembershipService.MembershipResult result = membershipService
                .requestToJoin(classId, userId(jwt));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(MembershipActionResponse.from(result.membership()));
    }

    @DeleteMapping("/classes/{classId}/membership-request")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Void> withdrawJoinRequest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId
    ) {
        membershipService.withdrawJoinRequest(classId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/classes/{classId}/membership")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Void> leaveClass(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId
    ) {
        membershipService.leaveClass(classId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/classes/{classId}/members/{classMemberId}/approve")
    @PreAuthorize("hasRole('TUTOR')")
    public ClassMemberResponse approveJoinRequest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId,
            @PathVariable UUID classMemberId
    ) {
        return ClassMemberResponse.from(membershipService.approveJoinRequest(
                classId, classMemberId, userId(jwt)
        ));
    }

    @PostMapping("/classes/{classId}/members/{classMemberId}/reject")
    @PreAuthorize("hasRole('TUTOR')")
    public ClassMemberResponse rejectJoinRequest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId,
            @PathVariable UUID classMemberId
    ) {
        return ClassMemberResponse.from(membershipService.rejectJoinRequest(
                classId, classMemberId, userId(jwt)
        ));
    }

    @DeleteMapping("/classes/{classId}/members/{classMemberId}")
    @PreAuthorize("hasRole('TUTOR')")
    public ResponseEntity<Void> removeMember(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID classId,
            @PathVariable UUID classMemberId
    ) {
        membershipService.removeMember(classId, classMemberId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    private UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
    }

    private Pageable pageable(
            int page,
            int size,
            String sort,
            Map<String, String> allowedFields
    ) {
        if (page < 1 || size < 1 || size > 100 || sort == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUERY_PARAMETER");
        }

        String[] parts = sort.split(",", -1);
        if (parts.length != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUERY_PARAMETER");
        }
        String property = allowedFields.get(parts[0].trim());
        if (property == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUERY_PARAMETER");
        }

        Sort.Direction direction;
        try {
            direction = Sort.Direction.fromString(parts[1].trim());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUERY_PARAMETER");
        }
        return PageRequest.of(page - 1, size, Sort.by(direction, property));
    }
}
