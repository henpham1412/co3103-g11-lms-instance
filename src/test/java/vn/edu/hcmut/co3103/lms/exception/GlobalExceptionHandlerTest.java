package vn.edu.hcmut.co3103.lms.exception;

import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.core.TypeInformation;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.hcmut.co3103.lms.dto.admin.UpdateUserRoleRequest;
import vn.edu.hcmut.co3103.lms.dto.admin.UpdateUserStatusRequest;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html", "application/json;q=0", "*/*"})
    void returnsConflictForLastAdminProtection(String accept) throws Exception {
        mockMvc.perform(get("/test/admin/last-admin").header("Accept", accept))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.code").value("INVALID_ROLE_OR_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message").value("The last active administrator cannot be demoted."))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html", "application/json;q=0", "*/*"})
    void returnsNotFoundForMissingUser(String accept) throws Exception {
        mockMvc.perform(get("/test/admin/missing-user").header("Accept", accept))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.code").value("LOCAL_USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("The requested user was not found."))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"illegal-argument", "property-reference"})
    void returnsSafeBadRequestForInvalidArguments(String path) throws Exception {
        mockMvc.perform(get("/test/admin/" + path))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Bad Request"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"role\":null}"})
    void returnsValidationDetailsForMissingRole(String body) throws Exception {
        mockMvc.perform(patch("/test/admin/role")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details.length()").value(1))
                .andExpect(jsonPath("$.details[0].field").value("role"))
                .andExpect(jsonPath("$.details[0].message").value("Role is required"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\":null}"})
    void returnsValidationDetailsForMissingStatus(String body) throws Exception {
        mockMvc.perform(patch("/test/admin/status")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details.length()").value(1))
                .andExpect(jsonPath("$.details[0].field").value("status"))
                .andExpect(jsonPath("$.details[0].message").value("Access status is required"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"STUDENT", "TUTOR", "ADMINISTRATOR"})
    void acceptsValidRoleRequests(String role) throws Exception {
        mockMvc.perform(patch("/test/admin/role")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"" + role + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(role));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "SUSPENDED"})
    void acceptsValidStatusRequests(String accessStatus) throws Exception {
        mockMvc.perform(patch("/test/admin/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + accessStatus + "\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(accessStatus));
    }

    @TestComponent
    @RestController
    static class TestController {

        @GetMapping("/test/admin/illegal-argument")
        public void illegalArgument() {
            throw new IllegalArgumentException("Internal implementation detail");
        }

        @GetMapping("/test/admin/property-reference")
        public void propertyReference() {
            throw new PropertyReferenceException("noSuchProperty", TypeInformation.of(UpdateUserRoleRequest.class),
                    List.of());
        }

        @GetMapping("/test/admin/last-admin")
        public void lastAdmin() {
            throw new LastAdminProtectionException("The last active administrator cannot be demoted.");
        }

        @GetMapping("/test/admin/missing-user")
        public void missingUser() {
            throw new UserNotFoundException("The requested user was not found.");
        }

        @PatchMapping("/test/admin/role")
        public String updateRole(@Valid @RequestBody UpdateUserRoleRequest request) {
            return request.role().name();
        }

        @PatchMapping("/test/admin/status")
        public String updateStatus(@Valid @RequestBody UpdateUserStatusRequest request) {
            return request.status().name();
        }
    }
}
