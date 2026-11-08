package vn.edu.hcmut.co3103.lms.exception;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.core.TypeInformation;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import vn.edu.hcmut.co3103.lms.dto.admin.UpdateUserRoleRequest;
import vn.edu.hcmut.co3103.lms.dto.admin.UpdateUserStatusRequest;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController(), new ErrorProbe())
                .setControllerAdvice(new ClassManagementExceptionHandler(), new GlobalExceptionHandler())
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

    @ParameterizedTest
    @CsvSource({
            "class-code, 409, CLASS_CODE_ALREADY_EXISTS, The class code is already in use.",
            "data-conflict, 409, RESOURCE_CONFLICT, The request conflicts with existing data.",
            "upload-too-large, 413, FILE_TOO_LARGE, The uploaded file exceeds the maximum allowed size.",
            "missing-resource, 404, NOT_FOUND, Not Found"
    })
    void preservesMergedErrorEnvelope(String path, int status, String code, String message) throws Exception {
        mockMvc.perform(get("/test/errors/" + path).header("Accept", "text/html"))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @TestComponent
    @RestController
    static class TestController {

        @GetMapping("/test/errors/class-code")
        public void classCodeConflict() {
            throw new ClassCodeAlreadyExistsException();
        }

        @GetMapping("/test/errors/data-conflict")
        public void dataConflict() {
            throw new DataIntegrityViolationException("Internal database detail");
        }

        @GetMapping("/test/errors/upload-too-large")
        public void uploadTooLarge() {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE");
        }

        @GetMapping("/test/errors/missing-resource")
        public void missingResource() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "private/resource", "Internal resource detail");
        }

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

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void mapsEachBusinessCodeToItsStatusAndDoc05Envelope(ErrorCode code) throws Exception {
        mockMvc.perform(get("/test/business").param("code", code.name()))
                .andExpect(status().is(code.getStatus().value()))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.message").value(code.getMessage()))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void beanValidationIs400ButDatabaseAndHibernateConstraintsAre409() throws Exception {
        mockMvc.perform(get("/test/failure").param("kind", "bean"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        for (String kind : Set.of("database", "hibernate")) {
            mockMvc.perform(get("/test/failure").param("kind", kind))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DATA_CONFLICT"))
                    .andExpect(content().string(not(containsString("secret"))));
        }
    }

    @Test
    void recognizesTheKnownTopicNameDatabaseConstraint() throws Exception {
        mockMvc.perform(get("/test/failure").param("kind", "unique"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKILL_TOPIC_NAME_DUPLICATE"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void unexpectedRuntimeExceptionsReturnSafe500RatherThan400() throws Exception {
        mockMvc.perform(get("/test/failure").param("kind", "unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.getMessage()))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void securityFailureReturns403() throws Exception {
        mockMvc.perform(get("/test/failure").param("kind", "security"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CLASS_ACCESS_DENIED"));
    }

    @Test
    void errorCodesAreUnique() {
        assertEquals(ErrorCode.values().length,
                Arrays.stream(ErrorCode.values()).map(Enum::name).distinct().count());
    }

    @Test
    void missingRequiredParameterIs400() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void unsupportedMethodIs405AndPreservesAllowHeader() throws Exception {
        mockMvc.perform(post("/test/business"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedMediaTypeIs415() throws Exception {
        mockMvc.perform(post("/test/body").contentType("text/plain").content("private rejected body"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(content().string(not(containsString("private rejected body"))));
    }

    @Test
    void unacceptableResponseTypeIs406() throws Exception {
        mockMvc.perform(post("/test/body").contentType("application/json").accept("text/plain").content("{}"))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void malformedJsonIs400AndMissingRouteIs404() throws Exception {
        mockMvc.perform(post("/test/body").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/test/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @TestComponent
    @RestController
    static class ErrorProbe {
        @PostMapping(value = "/test/body", consumes = "application/json", produces = "application/json")
        public Map<String, String> body(@RequestBody Map<String, String> body) {
            return body;
        }

        @GetMapping("/test/business")
        public void business(@RequestParam("code") String code) {
            throw new AppException(ErrorCode.valueOf(code));
        }

        @GetMapping("/test/failure")
        public void failure(@RequestParam("kind") String kind) {
            switch (kind) {
                case "bean" -> throw new ConstraintViolationException("secret rejected value", Set.of());
                case "database" -> throw new DataIntegrityViolationException("secret database details");
                case "hibernate" -> throw constraint("fk_secret");
                case "unique" -> throw new DataIntegrityViolationException(
                        "secret database details", constraint("uq_skill_topics_class_name"));
                case "security" -> throw new SecurityException("secret policy details");
                default -> throw new IllegalStateException("secret database state");
            }
        }

        private org.hibernate.exception.ConstraintViolationException constraint(String name) {
            return new org.hibernate.exception.ConstraintViolationException(
                    "secret SQL details", new SQLException("secret database details"), name);
        }
    }
}
