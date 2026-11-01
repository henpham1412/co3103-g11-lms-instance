package vn.edu.hcmut.co3103.lms.dto.admin;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void rejectsMissingRole() {
        assertThat(validator.validate(new UpdateUserRoleRequest(null)))
                .singleElement().satisfies(violation -> {
                    assertThat(violation.getPropertyPath().toString()).isEqualTo("role");
                    assertThat(violation.getMessage()).isEqualTo("Role is required");
                });
    }

    @ParameterizedTest
    @EnumSource(LmsRole.class)
    void acceptsEveryRole(LmsRole role) {
        assertThat(validator.validate(new UpdateUserRoleRequest(role))).isEmpty();
    }

    @Test
    void rejectsMissingStatus() {
        assertThat(validator.validate(new UpdateUserStatusRequest(null)))
                .singleElement().satisfies(violation -> {
                    assertThat(violation.getPropertyPath().toString()).isEqualTo("status");
                    assertThat(violation.getMessage()).isEqualTo("Access status is required");
                });
    }

    @ParameterizedTest
    @EnumSource(AccessStatus.class)
    void acceptsEveryStatus(AccessStatus status) {
        assertThat(validator.validate(new UpdateUserStatusRequest(status))).isEmpty();
    }
}
