package vn.edu.hcmut.co3103.lms.domain;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LmsUserAccessTest {

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
    void initializesMissingTimestampsBeforeInsert() {
        LmsUserAccess user = newUser();
        Instant before = Instant.now();

        user.initializeTimestamps();

        assertThat(user.getCreatedAt()).isBetween(before, Instant.now());
        assertThat(user.getUpdatedAt()).isEqualTo(user.getCreatedAt());
        assertThat(validator.validate(user)).isEmpty();
    }

    @Test
    void preservesExplicitTimestampsBeforeInsert() {
        Instant createdAt = Instant.parse("2020-01-01T00:00:00Z");
        Instant updatedAt = createdAt.plusSeconds(60);
        LmsUserAccess user = new LmsUserAccess(UUID.randomUUID(), "Test User",
                "user@example.com", LmsRole.STUDENT, AccessStatus.ACTIVE,
                null, createdAt, updatedAt);

        user.initializeTimestamps();

        assertThat(user.getCreatedAt()).isEqualTo(createdAt);
        assertThat(user.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2020-01-01T00:00:00Z", "2100-01-01T00:00:00Z"})
    void refreshesExistingTimestampBeforeEveryUpdate(String timestamp) {
        LmsUserAccess user = newUser();
        user.initializeTimestamps();
        Instant createdAt = user.getCreatedAt();
        user.setUpdatedAt(Instant.parse(timestamp));
        Instant before = Instant.now();

        user.initializeUpdatedAt();

        assertThat(user.getCreatedAt()).isEqualTo(createdAt);
        assertThat(user.getUpdatedAt()).isBetween(before, Instant.now());
    }

    @Test
    void initializesMissingUpdatedAtBeforeUpdate() {
        LmsUserAccess user = newUser();
        user.initializeTimestamps();
        Instant createdAt = user.getCreatedAt();
        user.setUpdatedAt(null);
        Instant before = Instant.now();

        user.initializeUpdatedAt();

        assertThat(user.getCreatedAt()).isEqualTo(createdAt);
        assertThat(user.getUpdatedAt()).isBetween(before, Instant.now());
    }

    @Test
    void rejectsMissingRequiredFields() {
        assertThat(validator.validate(new LmsUserAccess()))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("userId", "fullName", "email", "lmsRole", "accessStatus");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsBlankNames(String fullName) {
        LmsUserAccess user = newUser();
        user.setFullName(fullName);

        assertThat(validator.validateProperty(user, "fullName")).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {101, 150})
    void acceptsNamesUpTo150Characters(int length) {
        LmsUserAccess user = newUser();
        user.setFullName("N".repeat(length));

        assertThat(validator.validateProperty(user, "fullName")).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-an-email", "user@", "@example.com"})
    void rejectsInvalidEmails(String email) {
        LmsUserAccess user = newUser();
        user.setEmail(email);

        assertThat(validator.validateProperty(user, "email")).isNotEmpty();
    }

    @Test
    void rejectsValuesExceedingColumnLengths() {
        LmsUserAccess user = newUser();
        user.setFullName("N".repeat(151));
        user.setEmail("a".repeat(64) + "@" + "b".repeat(63) + "."
                + "c".repeat(63) + "." + "d".repeat(63));

        assertThat(validator.validate(user))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("fullName", "email");
    }

    private LmsUserAccess newUser() {
        return new LmsUserAccess(UUID.randomUUID(), "Test User", "user@example.com",
                LmsRole.STUDENT, AccessStatus.ACTIVE, null, null, null);
    }
}
