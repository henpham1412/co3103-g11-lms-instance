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
        var user = new AdminUserResponse(USER_ID, LmsRole.TUTOR, AccessStatus.ACTIVE, CREATED_AT, UPDATED_AT);
        when(service.getUsers(" Alice ", LmsRole.TUTOR, AccessStatus.ACTIVE, pageable))
                .thenReturn(new AdminUserPageResponse(List.of(user), 1, 2, 3, 2, true));

        mockMvc.perform(get(USERS).param("q", " Alice ").param("role", "TUTOR").param("status", "ACTIVE")
                        .param("page", "1").param("size", "2").param("sort", "fullName,asc"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.content[0].role").value("TUTOR"))
                .andExpect(jsonPath("$.content[0].accessStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.content[0].createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.content[0].updatedAt").value(UPDATED_AT.toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.last").value(true));

        verify(service).getUsers(" Alice ", LmsRole.TUTOR, AccessStatus.ACTIVE, pageable);
    }

    @ParameterizedTest
    @CsvSource({", ,", "alice, ,", ", STUDENT,", ", , SUSPENDED"})
    void usesDefaultPaginationWithOptionalFilters(String query, LmsRole role, AccessStatus accessStatus)
            throws Exception {
        var pageable = PageRequest.of(0, 20, Sort.by("createdAt").descending());
        when(service.getUsers(query, role, accessStatus, pageable))
                .thenReturn(new AdminUserPageResponse(List.of(), 0, 20, 0, 0, true));
        var request = get(USERS);
        if (query != null) request.param("q", query);
        if (role != null) request.param("role", role.name());
        if (accessStatus != null) request.param("status", accessStatus.name());

        mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
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
                accessStatus == null ? AccessStatus.ACTIVE : accessStatus, CREATED_AT, UPDATED_AT);
        when(service.updateUserAccess(USER_ID, request, ADMIN_ID)).thenReturn(response);

        mockMvc.perform(patch(USERS + "/{userId}", USER_ID).principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.role").value(response.role().name()))
                .andExpect(jsonPath("$.accessStatus").value(response.accessStatus().name()))
                .andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.updatedAt").value(UPDATED_AT.toString()));

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
                .andExpect(errorEnvelope("BAD_REQUEST", "Bad Request"));

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
                        CREATED_AT, UPDATED_AT));

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
                .andExpect(errorEnvelope("BAD_REQUEST", "Bad Request"));

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
                .andExpect(errorEnvelope("USER_NOT_FOUND", "User not found"));
    }

    @Test
    void returnsBadRequestWhenTheLastAdminIsProtected() throws Exception {
        String message = "Cannot demote or suspend the last active administrator";
        when(service.updateUserAccess(ADMIN_ID, new UpdateUserAccessRequest(null, AccessStatus.SUSPENDED), ADMIN_ID))
                .thenThrow(new LastAdminProtectionException(message));

        mockMvc.perform(patch(USERS + "/{userId}", ADMIN_ID).principal(ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accessStatus\":\"SUSPENDED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(errorEnvelope("LAST_ADMIN_PROTECTION", message));
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
}
