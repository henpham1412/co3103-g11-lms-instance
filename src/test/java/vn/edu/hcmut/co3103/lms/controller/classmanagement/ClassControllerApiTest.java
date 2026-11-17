package vn.edu.hcmut.co3103.lms.controller.classmanagement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.bind.support.WebDataBinderFactory;
import vn.edu.hcmut.co3103.lms.dto.request.ClassCreationRequest;
import vn.edu.hcmut.co3103.lms.dto.request.ClassUpdateRequest;
import vn.edu.hcmut.co3103.lms.dto.response.ClassCatalogResponse;
import vn.edu.hcmut.co3103.lms.dto.response.ClassCreationResponse;
import vn.edu.hcmut.co3103.lms.dto.response.ClassDetailResponse;
import vn.edu.hcmut.co3103.lms.dto.response.StudentClassPageResponse;
import vn.edu.hcmut.co3103.lms.exception.GlobalExceptionHandler;
import vn.edu.hcmut.co3103.lms.exception.ClassManagementExceptionHandler;
import vn.edu.hcmut.co3103.lms.exception.ClassCodeAlreadyExistsException;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.mapper.ClassMapper;
import vn.edu.hcmut.co3103.lms.service.classmanagement.ClassMembershipService;
import vn.edu.hcmut.co3103.lms.service.classmanagement.ClassService;
import vn.edu.hcmut.co3103.lms.service.classmanagement.ClassThumbnailService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ClassControllerApiTest {
    private final ClassService classService = mock(ClassService.class);
    private final ClassMembershipService membershipService = mock(ClassMembershipService.class);
    private final ClassMapper mapper = new ClassMapper();
    private final ClassThumbnailService thumbnailService = mock(ClassThumbnailService.class);
    private final UUID tutorId = UUID.randomUUID();
    private final UUID classId = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ClassController controller = new ClassController(classService, membershipService, mapper, thumbnailService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationArgumentResolver())
                .setControllerAdvice(new ClassManagementExceptionHandler(), new GlobalExceptionHandler())
                .build();
    }

    @Test
    void catalogEndpointPassesDocumentedSearchFilterAndPageSortToService() throws Exception {
        when(classService.browseClasses(eq("intro"), eq(Class.EnrollmentPolicy.OPEN), any()))
                .thenReturn(new ClassCatalogResponse(List.of(), 2, 5, 6, 2));

        mvc.perform(get("/api/v1/classes")
                        .param("q", "intro")
                        .param("enrollment_policy", "OPEN")
                        .param("page", "2")
                        .param("size", "5")
                        .param("sort", "title,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.total_items").value(6))
                .andExpect(jsonPath("$.total_pages").value(2));

        var pageable = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(classService).browseClasses(eq("intro"), eq(Class.EnrollmentPolicy.OPEN), pageable.capture());
        assertEquals(1, pageable.getValue().getPageNumber());
        assertEquals(5, pageable.getValue().getPageSize());
        assertEquals("title", pageable.getValue().getSort().getOrderFor("title").getProperty());
    }

    @Test
    void myClassesEndpointFiltersByClassStatusAndUsesDefaultPageSize() throws Exception {
        var item = new StudentClassPageResponse.Item(classId, "CS101", "Intro", "Description",
                null, null, "OPEN", "ACTIVE", "ACTIVE",
                new StudentClassPageResponse.Tutor(tutorId, "Tutor", "tutor@example.test"),
                new StudentClassPageResponse.Progress(1, 2, 50, "IN_PROGRESS"));
        when(membershipService.listStudentClasses(any(), eq(Class.ClassStatus.ACTIVE), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(item), org.springframework.data.domain.PageRequest.of(0, 20), 1));

        mvc.perform(get("/api/v1/me/classes").with(jwtAs("STUDENT")).param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].class_status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].membership_status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].progress.progress_percentage").value(50))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20));

        var pageable = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(membershipService).listStudentClasses(any(), eq(Class.ClassStatus.ACTIVE), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(20, pageable.getValue().getPageSize());
    }

    @Test
    void catalogRejectsInvalidPageSizeAndSort() throws Exception {
        mvc.perform(get("/api/v1/classes").param("page", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/classes").param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/classes").param("sort", "unknown,asc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void classDetailEndpointSerializesInstructorEmail() throws Exception {
        UUID tutor = UUID.randomUUID();
        var detail = new ClassDetailResponse(classId, "CS101", "Intro to Java", "Basics",
                "None", null, "OPEN", "ACTIVE",
                new ClassDetailResponse.Tutor(tutor, "Tutor One", "tutor.one@example.test"), 4, 20);
        when(classService.getClassDetails(eq(classId), any(), eq(false))).thenReturn(detail);

        mvc.perform(get("/api/v1/classes/{id}", classId).with(jwtAs("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.class_id").value(classId.toString()))
                .andExpect(jsonPath("$.title").value("Intro to Java"))
                .andExpect(jsonPath("$.tutor.user_id").value(tutor.toString()))
                .andExpect(jsonPath("$.tutor.full_name").value("Tutor One"))
                .andExpect(jsonPath("$.tutor.email").value("tutor.one@example.test"));
    }

    @Test
    void catalogItemsKeepTutorNameAndIdOnly() throws Exception {
        var item = new ClassCatalogResponse.Item(classId, "CS101", "Intro to Java", "Basics",
                "None", null, "OPEN", "ACTIVE",
                new ClassCatalogResponse.Tutor(UUID.randomUUID(), "Tutor One"), 4, 20);
        when(classService.browseClasses(any(), any(), any()))
                .thenReturn(new ClassCatalogResponse(List.of(item), 1, 12, 1, 1));

        mvc.perform(get("/api/v1/classes").with(jwtAs("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].tutor.full_name").value("Tutor One"))
                .andExpect(jsonPath("$.items[0].tutor.email").doesNotExist());
    }

    @Test
    void fractionalCapacityOnCreateIsRejectedBeforeAnyClassIsWritten() throws Exception {
        mvc.perform(post("/api/v1/classes").with(jwtAs("TUTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Intro","class_code":"CS101",
                                 "max_capacity":1.5,"enrollment_policy":"OPEN"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isArray());

        org.mockito.Mockito.verify(classService, org.mockito.Mockito.never())
                .createClass(any(), any());
    }

    @Test
    void fractionalCapacityOnUpdateIsRejectedBeforeAnyClassIsWritten() throws Exception {
        mvc.perform(patch("/api/v1/classes/{id}", classId).with(jwtAs("TUTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"max_capacity\":2.9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isArray());

        org.mockito.Mockito.verify(classService, org.mockito.Mockito.never())
                .updateClass(any(), any(), any());
    }

    @Test
    void integerValuedCapacityStillBindsToTheExactRequestedNumber() throws Exception {
        when(classService.createClass(any(ClassCreationRequest.class), any())).thenReturn(
                new ClassCreationResponse(classId, tutorId, "CS101", "Intro", null, null,
                        20, Class.EnrollmentPolicy.OPEN, Class.ClassStatus.ACTIVE, null,
                        Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")));

        mvc.perform(post("/api/v1/classes").with(jwtAs("TUTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Intro\",\"class_code\":\"CS101\","
                                + "\"max_capacity\":20,\"enrollment_policy\":\"OPEN\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.max_capacity").value(20));

        var captor = org.mockito.ArgumentCaptor.forClass(ClassCreationRequest.class);
        verify(classService).createClass(captor.capture(), any());
        assertEquals(20, captor.getValue().getMaxCapacity());
    }

    @Test
    void tutorCreateEndpointReturns201AndClassPayload() throws Exception {
        UUID newId = UUID.randomUUID();
        when(classService.createClass(any(ClassCreationRequest.class), any())).thenReturn(
                new ClassCreationResponse(newId, tutorId, "CS101", "Intro", "Description", null,
                        20, Class.EnrollmentPolicy.OPEN, Class.ClassStatus.ACTIVE, null,
                        Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z")));

        mvc.perform(post("/api/v1/classes").with(jwtAs("TUTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"class_code":"CS101","title":"Intro","description":"Description",
                                 "max_capacity":20,"enrollment_policy":"OPEN"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.class_id").value(newId.toString()))
                .andExpect(jsonPath("$.tutor_id").value(tutorId.toString()))
                .andExpect(jsonPath("$.enrollment_policy").value("OPEN"));
    }

    @Test
    void tutorUpdateEndpointReturnsUpdatedClassPayload() throws Exception {
        Class updated = new Class(tutorId, "CS101", "Updated title", null, null, 20,
                Class.EnrollmentPolicy.OPEN);
        org.springframework.test.util.ReflectionTestUtils.setField(updated, "classId", classId);
        when(classService.updateClass(eq(classId), any(), any(ClassUpdateRequest.class))).thenReturn(updated);

        mvc.perform(patch("/api/v1/classes/{id}", classId).with(jwtAs("TUTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated title\",\"max_capacity\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.class_id").value(classId.toString()))
                .andExpect(jsonPath("$.title").value("Updated title"));
    }

    @Test
    void patchDistinguishesNullCapacityFromOmittedCapacity() throws Exception {
        Class updated = new Class(tutorId, "CS101", "Kept", null, null, 20,
                Class.EnrollmentPolicy.OPEN);
        org.springframework.test.util.ReflectionTestUtils.setField(updated, "classId", classId);
        when(classService.updateClass(eq(classId), any(), any(ClassUpdateRequest.class)))
                .thenReturn(updated);

        mvc.perform(patch("/api/v1/classes/{id}", classId).with(jwtAs("TUTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Kept\",\"max_capacity\":null}"))
                .andExpect(status().isOk());

        var explicitNull = org.mockito.ArgumentCaptor.forClass(ClassUpdateRequest.class);
        verify(classService).updateClass(eq(classId), any(), explicitNull.capture());
        assertTrue(explicitNull.getValue().isMaxCapacityProvided());
        assertNull(explicitNull.getValue().getMaxCapacity());

        mvc.perform(patch("/api/v1/classes/{id}", classId).with(jwtAs("TUTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Kept\"}"))
                .andExpect(status().isOk());

        var omitted = org.mockito.ArgumentCaptor.forClass(ClassUpdateRequest.class);
        verify(classService, org.mockito.Mockito.times(2))
                .updateClass(eq(classId), any(), omitted.capture());
        assertFalse(omitted.getValue().isMaxCapacityProvided());
    }

    @Test
    void tutorCanUploadClassThumbnailUsingMultipartContract() throws Exception {
        String url = "http://localhost:8081/class-thumbnails/thumbnail.png";
        when(thumbnailService.upload(eq(classId), eq(tutorId), any())).thenReturn(url);
        MockMultipartFile image = new MockMultipartFile(
                "file", "cover.png", MediaType.IMAGE_PNG_VALUE,
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}
        );

        mvc.perform(multipart("/api/v1/classes/{id}/thumbnail", classId)
                        .file(image).with(jwtAs("TUTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thumbnail_url").value(url));
        verify(thumbnailService).upload(eq(classId), eq(tutorId), any());
    }

    @Test
    void uploadWithoutTheFilePartUsesTheDocumentedErrorEnvelope() throws Exception {
        mvc.perform(multipart("/api/v1/classes/{id}/thumbnail", classId).with(jwtAs("TUTOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isArray());

        verify(thumbnailService, never()).upload(any(), any(), any());
    }

    @Test
    void uploadWithNonMultipartContentTypeIsRejectedBeforeTheServiceRuns() throws Exception {
        mvc.perform(post("/api/v1/classes/{id}/thumbnail", classId).with(jwtAs("TUTOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verify(thumbnailService, never()).upload(any(), any(), any());
    }

    @Test
    void invalidEnrollmentPolicyUsesTheDocumentedErrorEnvelope() throws Exception {
        mvc.perform(get("/api/v1/classes").param("enrollment_policy", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QUERY_PARAMETER"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void malformedJsonUsesTheDocumentedErrorEnvelope() throws Exception {
        mvc.perform(post("/api/v1/classes").with(jwtAs("TUTOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void classValidationUsesTheDocumentedErrorEnvelope() throws Exception {
        mvc.perform(post("/api/v1/classes").with(jwtAs("TUTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" \",\"class_code\":\"x\",\"enrollment_policy\":\"OPEN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details[0].field").isNotEmpty())
                .andExpect(jsonPath("$.details[0].code").value("INVALID_VALUE"));
    }

    @Test
    void duplicateClassCodeRaceUsesConflictEnvelope() throws Exception {
        when(classService.createClass(any(ClassCreationRequest.class), any()))
                .thenThrow(new ClassCodeAlreadyExistsException());

        mvc.perform(post("/api/v1/classes").with(jwtAs("TUTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Programming\",\"class_code\":\"CS101\",\"enrollment_policy\":\"OPEN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLASS_CODE_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void databaseUniqueConstraintRaceUsesSameConflictEnvelope() throws Exception {
        var sqlException = new java.sql.SQLException("do not expose this");
        var databaseCause = new org.hibernate.exception.ConstraintViolationException(
                "duplicate class code", sqlException, "uq_class_class_code");
        when(classService.createClass(any(ClassCreationRequest.class), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "database detail must not be exposed", databaseCause));

        mvc.perform(post("/api/v1/classes").with(jwtAs("TUTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Programming\",\"class_code\":\"CS101\",\"enrollment_policy\":\"OPEN\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLASS_CODE_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertFalse(
                        result.getResponse().getContentAsString().contains("database detail")));
    }

    @Test
    void openMembershipEndpointReturnsCreatedMembershipPayload() throws Exception {
        UUID studentId = UUID.randomUUID();
        var membership = new vn.edu.hcmut.co3103.lms.entity.ClassMember(classId, studentId,
                vn.edu.hcmut.co3103.lms.entity.ClassMember.MembershipStatus.ACTIVE, null, Instant.now());
        org.springframework.test.util.ReflectionTestUtils.setField(membership, "classMemberId", UUID.randomUUID());
        when(membershipService.joinOpenClass(eq(classId), any()))
                .thenReturn(new ClassMembershipService.MembershipResult(membership, true));

        mvc.perform(post("/api/v1/classes/{id}/membership", classId).with(jwtAs("STUDENT")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.class_id").value(classId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void approvalRequestEndpointReturnsPendingStateAnd201() throws Exception {
        UUID studentId = UUID.randomUUID();
        var membership = new vn.edu.hcmut.co3103.lms.entity.ClassMember(classId, studentId,
                vn.edu.hcmut.co3103.lms.entity.ClassMember.MembershipStatus.PENDING, Instant.now(), null);
        org.springframework.test.util.ReflectionTestUtils.setField(membership, "classMemberId", UUID.randomUUID());
        when(membershipService.requestToJoin(eq(classId), any()))
                .thenReturn(new ClassMembershipService.MembershipResult(membership, true));

        mvc.perform(post("/api/v1/classes/{id}/join-requests", classId).with(jwtAs("STUDENT")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.class_id").value(classId.toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void tutorDetailReadIsDelegatedToTheServiceWithoutAnOwnershipCheck() throws Exception {
        var detail = new ClassDetailResponse(classId, "CS101", "Intro to Java", "Basics",
                null, null, "OPEN", "ACTIVE",
                new ClassDetailResponse.Tutor(UUID.randomUUID(), "Other Tutor", "other@example.test"),
                4, 20);
        when(classService.getClassDetails(eq(classId), any(), eq(false))).thenReturn(detail);

        mvc.perform(get("/api/v1/classes/{id}", classId).with(jwtAs("TUTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tutor.full_name").value("Other Tutor"));
    }

    @Test
    void rosterEndpointPassesIdentitySearchAndPagingToService() throws Exception {
        UUID memberId = UUID.randomUUID();
        UUID student = UUID.randomUUID();
        var member = new vn.edu.hcmut.co3103.lms.entity.ClassMember(
                classId, student,
                vn.edu.hcmut.co3103.lms.entity.ClassMember.MembershipStatus.ACTIVE,
                null, Instant.now());
        org.springframework.test.util.ReflectionTestUtils.setField(member, "classMemberId", memberId);
        when(membershipService.listMembers(eq(classId), any(), eq("ada"),
                eq(vn.edu.hcmut.co3103.lms.entity.ClassMember.MembershipStatus.ACTIVE), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(member), org.springframework.data.domain.PageRequest.of(1, 20), 21L));

        mvc.perform(get("/api/v1/classes/{id}/members", classId).with(jwtAs("TUTOR"))
                        .param("q", "ada")
                        .param("status", "ACTIVE")
                        .param("page", "2")
                        .param("size", "20")
                        .param("sort", "joined_at,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].class_member_id").value(memberId.toString()))
                .andExpect(jsonPath("$.items[0].student_id").value(student.toString()))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.total_items").value(21))
                .andExpect(jsonPath("$.total_pages").value(2));

        var pageable = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(membershipService).listMembers(eq(classId), any(), eq("ada"),
                eq(vn.edu.hcmut.co3103.lms.entity.ClassMember.MembershipStatus.ACTIVE), pageable.capture());
        assertEquals(1, pageable.getValue().getPageNumber());
        assertEquals(20, pageable.getValue().getPageSize());
        assertEquals("joined_at",
                pageable.getValue().getSort().getOrderFor("joined_at").getProperty());
    }

    @Test
    void tutorApprovalEndpointReturnsUpdatedMembershipState() throws Exception {
        UUID memberId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        var active = new vn.edu.hcmut.co3103.lms.entity.ClassMember(classId, studentId,
                vn.edu.hcmut.co3103.lms.entity.ClassMember.MembershipStatus.ACTIVE, null, Instant.now());
        org.springframework.test.util.ReflectionTestUtils.setField(active, "classMemberId", memberId);
        when(membershipService.approveJoinRequest(classId, memberId, tutorId)).thenReturn(active);

        mvc.perform(post("/api/v1/classes/{classId}/members/{memberId}/approve", classId, memberId)
                        .with(jwtAs("TUTOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.class_member_id").value(memberId.toString()))
                .andExpect(jsonPath("$.student_id").value(studentId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void endpointsDeclareExpectedRoleRestrictions() throws Exception {
        assertRole("browseClasses", "hasRole('STUDENT')");
        assertRole("createClass", "hasRole('TUTOR')");
        assertRole("updateClass", "hasRole('TUTOR')");
        assertRole("uploadClassThumbnail", "hasRole('TUTOR')");
        assertRole("joinOpenClass", "hasRole('STUDENT')");
        assertRole("approveJoinRequest", "hasRole('TUTOR')");
    }

    private RequestPostProcessor jwtAs(String role) {
        UUID subject = role.equals("TUTOR") ? tutorId : UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        Authentication authentication = new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return request -> {
            request.setUserPrincipal(authentication);
            return request;
        };
    }

    private void assertRole(String methodName, String expression) throws Exception {
        var method = java.util.Arrays.stream(ClassController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst().orElseThrow();
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertNotNull(annotation, methodName + " must enforce a role");
        assertEquals(expression, annotation.value());
    }

    private static final class AuthenticationArgumentResolver implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return Jwt.class.isAssignableFrom(parameter.getParameterType())
                    || Authentication.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                                      NativeWebRequest request, WebDataBinderFactory binderFactory) {
            Authentication authentication = (Authentication) request.getUserPrincipal();
            if (Authentication.class.isAssignableFrom(parameter.getParameterType())) {
                return authentication;
            }
            return authentication == null ? null : authentication.getPrincipal();
        }
    }
}
