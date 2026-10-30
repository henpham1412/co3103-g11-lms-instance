package vn.edu.hcmut.co3103.lms.exception;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Test-only endpoints exercise advice dispatch; no production feature API is created. */
class GlobalExceptionHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ErrorProbe())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void mapsEachBusinessCodeToItsStatusAndDoc05Envelope(ErrorCode code) throws Exception {
        mvc.perform(get("/test/business").param("code", code.name()))
                .andExpect(status().is(code.getStatus().value()))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.message").value(code.getMessage()))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details").isEmpty())
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    @Test
    void beanValidationIs400ButDatabaseAndHibernateConstraintsAre409() throws Exception {
        mvc.perform(get("/test/failure").param("kind", "bean"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        for (String kind : Set.of("database", "hibernate")) {
            mvc.perform(get("/test/failure").param("kind", kind))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DATA_CONFLICT"))
                    .andExpect(content().string(not(containsString("secret"))));
        }
    }

    @Test
    void recognizesTheKnownTopicNameDatabaseConstraint() throws Exception {
        mvc.perform(get("/test/failure").param("kind", "unique"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKILL_TOPIC_NAME_DUPLICATE"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void unexpectedRuntimeExceptionsReturnSafe500RatherThan400() throws Exception {
        mvc.perform(get("/test/failure").param("kind", "unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.getMessage()))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void securityFailureReturns403() throws Exception {
        mvc.perform(get("/test/failure").param("kind", "security"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CLASS_ACCESS_DENIED"));
    }

    @Test
    void errorCodesAreUnique() {
        assertEquals(ErrorCode.values().length,
                Arrays.stream(ErrorCode.values()).map(Enum::name).distinct().count());
    }

    @RestController
    static class ErrorProbe {
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
