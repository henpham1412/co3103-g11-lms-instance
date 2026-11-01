package vn.edu.hcmut.co3103.lms.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.dto.admin.AdminUserPageResponse;
import vn.edu.hcmut.co3103.lms.dto.admin.AdminUserResponse;
import vn.edu.hcmut.co3103.lms.dto.admin.UpdateUserAccessRequest;
import vn.edu.hcmut.co3103.lms.exception.GlobalExceptionHandler;
import vn.edu.hcmut.co3103.lms.exception.LastAdminProtectionException;
import vn.edu.hcmut.co3103.lms.exception.UserNotFoundException;
import vn.edu.hcmut.co3103.lms.service.admin.AdminUserService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    private static final String USERS = "/api/admin/users";
    private static final UUID ADMIN_ID = new UUID(0, 1);
    private static final UUID USER_ID = new UUID(0, 2);
    private static final Instant CREATED_AT = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2025-02-01T00:00:00Z");
    private static final TestingAuthenticationToken ADMIN =
            new TestingAuthenticationToken(ADMIN_ID.toString(), null, "ROLE_ADMINISTRATOR");

    @Mock private AdminUserService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminUserController(service))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listsUsersWithFiltersAndExplicitPaginationAndSort() throws Exception {
        var pageable = PageRequest.of(1, 2, Sort.by("fullName").ascending());
        var user = new AdminUserResponse(USER_ID, LmsRole.TUTOR, AccessStatus.ACTIVE, CREATED_AT, UPDATED_AT, "Alice", "alice@example.test", CREATED_AT);
        when(service.getUsers(" Alice ", LmsRole.TUTOR, AccessStatus.ACTIVE, pageable))
                .thenReturn(new AdminUserPageResponse(List.of(user), 2, 2, 3, 2, true));

        mockMvc.perform(get(USERS).param("q", " Alice ").param("role", "TUTOR").param("status", "ACTIVE")
                        .param("page", "2").param("size", "2").param("sort", "fullName,asc"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].user_id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.items[0].lms_role").value("TUTOR"))
                .andExpect(jsonPath("$.items[0].access_status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].created_at").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.items[0].updated_at").value(UPDATED_AT.toString()))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total_items").value(3))
                .andExpect(jsonPath("$.total_pages").value(2))
                .andExpect(jsonPath("$.last").value(true));

        verify(service).getUsers(" Alice ", LmsRole.TUTOR, AccessStatus.ACTIVE, pageable);
    }

    @ParameterizedTest
    @CsvSource({", ,", "alice, ,", ", STUDENT,", ", , SUSPENDED"})
    void usesDefaultPaginationWithOptionalFilters(String query, LmsRole role, AccessStatus accessStatus)
            throws Exception {
        var pageable = PageRequest.of(0, 20, Sort.by("createdAt").descending());
        when(service.getUsers(query, role, accessStatus, pageable))
                .thenReturn(new AdminUserPageResponse(List.of(), 1, 20, 0, 0, true));
        var request = get(USERS);
        if (query != null) request.param("q", query);
        if (role != null) request.param("role", role.name());
        if (accessStatus != null) request.param("status", accessStatus.name());

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total_items").value(0))
                .andExpect(jsonPath("$.total_pages").value(0))
                .andExpect(jsonPath("$.last").value(true));

        verify(service).getUsers(query, role, accessStatus, pageable);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"role\":\"TUTOR\",\"accessStatus\":\"SUSPENDED\"}|TUTOR|SUSPENDED",
            "{\"role\":\"TUTOR\"}|TUTOR|",
            "{\"role\":\"STUDENT\"}|STUDENT|",
            "{\"role\":\"ADMINISTRATOR\"}|ADMINISTRATOR|",
            "{\"accessStatus\":\"SUSPENDED\"}||SUSPENDED",
            "{\"accessStatus\":\"ACTIVE\"}||ACTIVE",
            "{\"role\":null,\"accessStatus\":\"SUSPENDED\"}||SUSPENDED",
            "{\"role\":null,\"accessStatus\":null}||",
            "{}||"
    })
    void patchesAccessAndPassesTheAuthenticatedAdminId(String body, LmsRole role, AccessStatus accessStatus)
            throws Exception {
        var request = new UpdateUserAccessRequest(role, accessStatus);
        var response = new AdminUserResponse(USER_ID, role == null ? LmsRole.STUDENT : role,
                accessStatus == null ? AccessStatus.ACTIVE : accessStatus, CREATED_AT, UPDATED_AT, "Alice", "alice@example.test", CREATED_AT);
        when(service.updateUserAccess(USER_ID, request, ADMIN_ID)).thenReturn(response);

        mockMvc.perform(patch(USERS + "/{userId}", USER_ID).principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$.user_id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.lms_role").value(response.role().name()))
                .andExpect(jsonPath("$.access_status").value(response.accessStatus().name()))
                .andExpect(jsonPath("$.created_at").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.updated_at").value(UPDATED_AT.toString()));

        verify(service).updateUserAccess(USER_ID, request, ADMIN_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"role\":\"OWNER\"}", "{\"accessStatus\":\"DELETED\"}",
            "{\"role\":{}}", "{", "null", "[]", "",
            "{\"role\":2}", "{\"role\":\"2\"}", "{\"accessStatus\":1}", "{\"accessStatus\":\"1\"}",
            "{\"role\":true}", "{\"accessStatus\":[]}", "{\"role\":2.0}", "{\"accessStatus\":1.0}",
            "{\"role\":\"\"}", "{\"accessStatus\":\" \"}", "{\"role\":\"student\"}",
            "{\"role\":\" TUTOR \"}"})
    void rejectsInvalidPayloadsWithoutCallingTheService(String body) throws Exception {
        mockMvc.perform(patch(USERS + "/{userId}", USER_ID).principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(errorEnvelope(body.startsWith("{\"") ? "VALIDATION_ERROR" : "BAD_REQUEST",
                        body.startsWith("{\"") ? "Validation failed" : "Bad Request"));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "text/html", "application/json;q=0", "*/*;q=0",
            "application/json;q=0, */*;q=1", "*/*;q=1, application/json;q=0",
            "application/*;q=0, */*;q=1"})
    void rejectsUnacceptablePatchResponseWithoutCallingTheService(String accept) throws Exception {
        mockMvc.perform(patch(USERS + "/{userId}", USER_ID).principal(ADMIN)
                        .header("Accept", accept)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"TUTOR\"}"))
                .andExpect(status().isNotAcceptable())
                .andExpect(errorEnvelope("NOT_ACCEPTABLE", "Not Acceptable"));

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/*", "*/*", "application/json;q=0.1",
            "text/html, application/json;q=0.5", "application/json;q=1, */*;q=0",
            "*/*;q=0, application/json;q=1", "application/*;q=0, application/json;q=0.5"})
    void acceptsNegotiableJsonPatchResponses(String accept) throws Exception {
        var request = new UpdateUserAccessRequest(LmsRole.TUTOR, null);
        when(service.updateUserAccess(USER_ID, request, ADMIN_ID))
                .thenReturn(new AdminUserResponse(USER_ID, LmsRole.TUTOR, AccessStatus.ACTIVE,
                        CREATED_AT, UPDATED_AT, "Alice", "alice@example.test", CREATED_AT));

        mockMvc.perform(patch(USERS + "/{userId}", USER_ID).principal(ADMIN)
                        .header("Accept", accept)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"TUTOR\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        verify(service).updateUserAccess(USER_ID, request, ADMIN_ID);
    }

    @ParameterizedTest
    @CsvSource({"role, OWNER", "status, DELETED"})
    void rejectsInvalidFilters(String parameter, String value) throws Exception {
        mockMvc.perform(get(USERS).param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(errorEnvelope("VALIDATION_ERROR", "Validation failed"));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedUserId() throws Exception {
        mockMvc.perform(patch(USERS + "/not-a-uuid").principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"TUTOR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorEnvelope("BAD_REQUEST", "Bad Request"));

        verifyNoInteractions(service);
    }

    @Test
    void returnsNotFoundForAnUnknownUser() throws Exception {
        when(service.updateUserAccess(USER_ID, new UpdateUserAccessRequest(LmsRole.TUTOR, null), ADMIN_ID))
                .thenThrow(new UserNotFoundException("User not found"));

        mockMvc.perform(patch(USERS + "/{userId}", USER_ID).principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"TUTOR\"}"))
                .andExpect(status().isNotFound())
                .andExpect(errorEnvelope("LOCAL_USER_NOT_FOUND", "User not found"));
    }

    @Test
    void returnsConflictWhenTheLastAdminIsProtected() throws Exception {
        String message = "Cannot demote or suspend the last active administrator";
        when(service.updateUserAccess(ADMIN_ID, new UpdateUserAccessRequest(null, AccessStatus.SUSPENDED), ADMIN_ID))
                .thenThrow(new LastAdminProtectionException(message));

        mockMvc.perform(patch(USERS + "/{userId}", ADMIN_ID).principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accessStatus\":\"SUSPENDED\"}"))
                .andExpect(status().isConflict())
                .andExpect(errorEnvelope("INVALID_ROLE_OR_STATUS_TRANSITION", message));
    }

    private static ResultMatcher errorEnvelope(String code, String message) {
        return result -> {
            content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON).match(result);
            jsonPath("$.length()").value(3).match(result);
            jsonPath("$.code").value(code).match(result);
            jsonPath("$.message").value(message).match(result);
            jsonPath("$.details").isArray().match(result);
            jsonPath("$.details").isEmpty().match(result);
        };
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "page,0", "page,abc", "page,2147483648", "page,1.5", "page,''",
            "size,-1", "size,0", "size,abc", "size,2147483648", "size,1.5", "size,''", "size,2001"})
    void invalidPaginationIsRejectedBeforeQuery(String parameter, String value) throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(errorEnvelope("VALIDATION_ERROR", "Validation failed"));
        verifyNoInteractions(service);
    }

    @Test
    void overflowingOffsetIsRejectedBeforeQuery() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").param("page", "2147483647").param("size", "2"))
                .andExpect(status().isBadRequest())
                .andExpect(errorEnvelope("VALIDATION_ERROR", "Validation failed"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sort=unknown,asc", "sort=created_at,banana", "sort=", "sort=,asc",
            "sort=created_at,", "sort=created_at,asc,desc", "sort=full_name,asc&sort=unknown,asc",
            "page=1&page=1", "size=1&size=2", "q=one&q=two",
            "lms_role=TUTOR&role=TUTOR", "lms_role=TUTOR&role=STUDENT",
            "lms_role=TUTOR&lms_role=STUDENT", "role=TUTOR&role=TUTOR",
            "access_status=ACTIVE&status=ACTIVE", "access_status=ACTIVE&status=SUSPENDED",
            "access_status=ACTIVE&access_status=SUSPENDED", "status=ACTIVE&status=ACTIVE"})
    void adminQueryValidationUsesDoc05CodeBeforeQuery(String query) throws Exception {
        for (String route : List.of("/api/v1/admin/users", USERS)) {
            mockMvc.perform(get(route + "?" + query))
                    .andExpect(status().isBadRequest())
                    .andExpect(errorEnvelope("VALIDATION_ERROR", "Validation failed"));
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json;q=0", "application/json;q=0, */*;q=1",
            "application/json;charset=ISO-8859-1", "application/problem+json"})
    void getEndpointsRejectUnacceptableResponsesBeforeQuery(String accept) throws Exception {
        for (String path : List.of("/api/v1/admin/users", "/api/v1/admin/users/" + USER_ID)) {
            mockMvc.perform(get(path).header("Accept", accept))
                    .andExpect(status().isNotAcceptable())
                    .andExpect(errorEnvelope("NOT_ACCEPTABLE", "Not Acceptable"));
        }
        verifyNoInteractions(service);
    }
}
