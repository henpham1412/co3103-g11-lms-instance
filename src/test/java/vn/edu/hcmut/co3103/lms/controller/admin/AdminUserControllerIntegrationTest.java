package vn.edu.hcmut.co3103.lms.controller.admin;

import com.nimbusds.jose.JOSEException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.schemas=admin_user_controller_test",
        "spring.jpa.properties.hibernate.default_schema=admin_user_controller_test"
})
@AutoConfigureMockMvc
@Transactional
@Import(AdminUserControllerIntegrationTest.JwtClockConfiguration.class)
class AdminUserControllerIntegrationTest {

    private static final String USERS = "/api/admin/users";
    private static final Instant ORIGINAL_TIME = Instant.parse("2025-01-01T00:00:00Z");
    private static final JwtTestSupport TOKENS = startTokens();

    @Autowired private MockMvc mockMvc;
    @Autowired private LmsUserAccessRepository users;
    @Autowired private EntityManager entityManager;

    private LmsUserAccess admin;
    private LmsUserAccess target;

    private static JwtTestSupport startTokens() {
        try {
            return new JwtTestSupport();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> JwtTestSupport.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TOKENS::jwksUri);
    }

    @AfterAll
    static void stopJwksServer() {
        TOKENS.close();
    }

    @BeforeEach
    void setUp() {
        admin = saveUser("Admin", LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE, ORIGINAL_TIME.plusSeconds(1));
        target = saveUser("Student", LmsRole.STUDENT, AccessStatus.ACTIVE, ORIGINAL_TIME);
    }

    @Test
    void administratorCanFilterAndPaginatePersistedUsers() throws Exception {
        saveUser("Alice A", LmsRole.TUTOR, AccessStatus.ACTIVE, ORIGINAL_TIME);
        var second = saveUser("Alice B", LmsRole.TUTOR, AccessStatus.ACTIVE, ORIGINAL_TIME);
        saveUser("Alice Student", LmsRole.STUDENT, AccessStatus.ACTIVE, ORIGINAL_TIME);
        saveUser("Alice Suspended", LmsRole.TUTOR, AccessStatus.SUSPENDED, ORIGINAL_TIME);
        saveUser("Bob", LmsRole.TUTOR, AccessStatus.ACTIVE, ORIGINAL_TIME);

        mockMvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, bearer(admin.getUserId()))
                        .param("q", " ALICE ").param("role", "TUTOR").param("status", "ACTIVE")
                        .param("page", "2").param("size", "1").param("sort", "fullName,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].user_id").value(second.getUserId().toString()))
                .andExpect(jsonPath("$.items[0].lms_role").value("TUTOR"))
                .andExpect(jsonPath("$.items[0].access_status").value("ACTIVE"))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.total_items").value(2))
                .andExpect(jsonPath("$.total_pages").value(2))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void defaultsToTheFirstTwentyUsersOrderedByCreationDescending() throws Exception {
        mockMvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, bearer(admin.getUserId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].user_id").value(admin.getUserId().toString()))
                .andExpect(jsonPath("$.items[1].user_id").value(target.getUserId().toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total_items").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"noSuchProperty", "role", "id", "userId.noSuchProperty"})
    void rejectsUnknownSortWithValidationError(String property) throws Exception {
        mockMvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, bearer(admin.getUserId()))
                        .param("sort", "createdAt,desc", property + ",asc"))
                .andExpect(status().isBadRequest())
                .andExpect(errorEnvelope("VALIDATION_ERROR", "Validation failed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"userId", "fullName", "email", "lmsRole", "accessStatus",
            "lastLoginAt", "createdAt", "updatedAt"})
    void acceptsDocumentedSortProperties(String property) throws Exception {
        mockMvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, bearer(admin.getUserId()))
                        .param("sort", property + ",asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_items").value(2));
    }

    @ParameterizedTest
    @CsvSource({"2147483647, 20", "1073741825, 2"})
    void rejectsPageOffsetOverflowWithValidationError(String page, String size) throws Exception {
        mockMvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, bearer(admin.getUserId()))
                        .param("page", page).param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(errorEnvelope("VALIDATION_ERROR", "Validation failed"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/xml", "text/html", "application/json;q=0", "*/*;q=0",
            "application/json;q=0, */*;q=1", "*/*;q=1, application/json;q=0",
            "application/*;q=0, */*;q=1"})
    void rejectsUnacceptablePatchResponseBeforeCommit(String accept) throws Exception {
        // Commit fixtures so PATCH owns its transaction, just as it does in production.
        TestTransaction.flagForCommit();
        TestTransaction.end();
        try {
            mockMvc.perform(patch(USERS + "/{userId}", target.getUserId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin.getUserId()))
                            .header(HttpHeaders.ACCEPT, accept)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\":\"TUTOR\",\"accessStatus\":\"SUSPENDED\"}"))
                    .andExpect(status().isNotAcceptable())
                    .andExpect(errorEnvelope("NOT_ACCEPTABLE", "Not Acceptable"));

            assertTargetAccessUnchanged();
        } finally {
            users.deleteAllById(List.of(admin.getUserId(), target.getUserId()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"role\":0}", "{\"role\":1}", "{\"role\":2}",
            "{\"role\":\"0\"}", "{\"role\":\"1\"}", "{\"role\":\"2\"}", "{\"role\":\" 2 \"}",
            "{\"accessStatus\":0}", "{\"accessStatus\":1}",
            "{\"accessStatus\":\"0\"}", "{\"accessStatus\":\"1\"}",
            "{\"role\":\"ADMINISTRATOR\",\"accessStatus\":1}",
            "{\"role\":2,\"accessStatus\":\"SUSPENDED\"}"})
    void rejectsNumericAccessEnumsWithoutMutation(String body) throws Exception {
        TestTransaction.flagForCommit();
        TestTransaction.end();
        try {
            mockMvc.perform(patch(USERS + "/{userId}", target.getUserId())
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin.getUserId()))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(errorEnvelope("VALIDATION_ERROR", "Validation failed"));

            assertTargetAccessUnchanged();
        } finally {
            users.deleteAllById(List.of(admin.getUserId(), target.getUserId()));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"role\":\"TUTOR\",\"accessStatus\":\"SUSPENDED\"}|TUTOR|SUSPENDED",
            "{\"role\":\"TUTOR\"}|TUTOR|ACTIVE",
            "{\"accessStatus\":\"SUSPENDED\"}|STUDENT|SUSPENDED"
    })
    void administratorCanPersistFullAndPartialUpdates(String body, LmsRole role, AccessStatus accessStatus)
            throws Exception {
        mockMvc.perform(patch(USERS + "/{userId}", target.getUserId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.getUserId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(target.getUserId().toString()))
                .andExpect(jsonPath("$.lms_role").value(role.name()))
                .andExpect(jsonPath("$.access_status").value(accessStatus.name()))
                .andExpect(jsonPath("$.created_at").value(ORIGINAL_TIME.toString()))
                .andExpect(jsonPath("$.updated_at").isString());

        entityManager.clear();
        var stored = users.findById(target.getUserId()).orElseThrow();
        assertThat(stored.getLmsRole()).isEqualTo(role);
        assertThat(stored.getAccessStatus()).isEqualTo(accessStatus);
        assertThat(stored.getUpdatedAt()).isAfter(ORIGINAL_TIME);
        assertThat(stored.getFullName()).isEqualTo("Student");
        assertThat(stored.getEmail()).isEqualTo(target.getEmail());
    }

    @ParameterizedTest
    @EnumSource(value = LmsRole.class, names = {"STUDENT", "TUTOR"})
    void nonAdministratorsCannotListUsersOrEscalateTheirOwnRole(LmsRole role) throws Exception {
        target.setLmsRole(role);
        users.saveAndFlush(target);
        String authorization = bearer(target.getUserId());

        mockMvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(forbidden());
        mockMvc.perform(patch(USERS + "/{userId}", target.getUserId())
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMINISTRATOR\"}"))
                .andExpect(forbidden());

        entityManager.clear();
        assertThat(users.findById(target.getUserId()).orElseThrow().getLmsRole()).isEqualTo(role);
    }

    @Test
    void suspendedAdministratorCannotUseEitherEndpoint() throws Exception {
        admin.setAccessStatus(AccessStatus.SUSPENDED);
        users.saveAndFlush(admin);
        assertBothEndpointsForbidden(bearer(admin.getUserId()));
    }

    @Test
    void identityWithoutLocalAccessCannotUseEitherEndpoint() throws Exception {
        assertBothEndpointsForbidden(bearer(UUID.randomUUID()));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "''|A valid Bearer access token is required.",
            "Bearer malformed-token|The Bearer access token is invalid or expired."
    })
    void missingOrInvalidJwtIsUnauthorizedOnBothEndpoints(String authorization, String message) throws Exception {
        var list = get(USERS);
        var update = patch(USERS + "/{userId}", target.getUserId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"TUTOR\"}");
        if (!authorization.isEmpty()) {
            list.header(HttpHeaders.AUTHORIZATION, authorization);
            update.header(HttpHeaders.AUTHORIZATION, authorization);
        }

        mockMvc.perform(list).andExpect(unauthorized(message));
        mockMvc.perform(update).andExpect(unauthorized(message));
        entityManager.clear();
        assertThat(users.findById(target.getUserId()).orElseThrow().getLmsRole()).isEqualTo(LmsRole.STUDENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"role\":\"OWNER\"}", "{\"accessStatus\":\"DELETED\"}", "null", "{"})
    void invalidPayloadReturnsBadRequestWithoutChangingAccess(String body) throws Exception {
        mockMvc.perform(patch(USERS + "/{userId}", target.getUserId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.getUserId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(errorEnvelope(body.startsWith("{\"") ? "VALIDATION_ERROR" : "BAD_REQUEST",
                        body.startsWith("{\"") ? "Validation failed" : "Bad Request"));

        entityManager.clear();
        var stored = users.findById(target.getUserId()).orElseThrow();
        assertThat(stored.getLmsRole()).isEqualTo(LmsRole.STUDENT);
        assertThat(stored.getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(stored.getUpdatedAt()).isEqualTo(ORIGINAL_TIME);
    }

    @Test
    void unknownUserReturnsNotFound() throws Exception {
        mockMvc.perform(patch(USERS + "/{userId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.getUserId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"TUTOR\"}"))
                .andExpect(status().isNotFound())
                .andExpect(errorEnvelope("LOCAL_USER_NOT_FOUND", "User not found"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"role\":\"STUDENT\"}", "{\"accessStatus\":\"SUSPENDED\"}"})
    void lastActiveAdminCannotDemoteOrSuspendThemself(String body) throws Exception {
        saveUser("Suspended Admin", LmsRole.ADMINISTRATOR, AccessStatus.SUSPENDED, ORIGINAL_TIME);
        mockMvc.perform(patch(USERS + "/{userId}", admin.getUserId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.getUserId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(errorEnvelope("INVALID_ROLE_OR_STATUS_TRANSITION",
                        "Cannot demote or suspend the last active administrator"));

        entityManager.clear();
        var stored = users.findById(admin.getUserId()).orElseThrow();
        assertThat(stored.getLmsRole()).isEqualTo(LmsRole.ADMINISTRATOR);
        assertThat(stored.getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"role\":\"TUTOR\"}", "{\"accessStatus\":\"SUSPENDED\"}"})
    void revokingAdminAccessTakesEffectOnTheNextRequestWithTheSameJwt(String body) throws Exception {
        saveUser("Other Admin", LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE, ORIGINAL_TIME);
        String authorization = bearer(admin.getUserId());
        mockMvc.perform(patch(USERS + "/{userId}", admin.getUserId())
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        entityManager.clear();
        assertBothEndpointsForbidden(authorization);
    }

    private void assertBothEndpointsForbidden(String authorization) throws Exception {
        mockMvc.perform(get(USERS).header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(forbidden());
        mockMvc.perform(patch(USERS + "/{userId}", target.getUserId())
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"TUTOR\"}"))
                .andExpect(forbidden());
        entityManager.clear();
        assertThat(users.findById(target.getUserId()).orElseThrow().getLmsRole()).isEqualTo(LmsRole.STUDENT);
    }

    private void assertTargetAccessUnchanged() {
        var stored = users.findById(target.getUserId()).orElseThrow();
        assertThat(stored.getLmsRole()).isEqualTo(LmsRole.STUDENT);
        assertThat(stored.getAccessStatus()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(stored.getUpdatedAt()).isEqualTo(ORIGINAL_TIME);
    }

    private LmsUserAccess saveUser(String name, LmsRole role, AccessStatus accessStatus, Instant createdAt) {
        UUID id = UUID.randomUUID();
        return users.saveAndFlush(new LmsUserAccess(id, name, id + "@example.test", role, accessStatus,
                null, createdAt, ORIGINAL_TIME));
    }

    private String bearer(UUID subject) throws JOSEException {
        var claims = TOKENS.claims();
        claims.put("sub", subject.toString());
        // These claims must never override the role stored in the LMS database.
        claims.put("roles", List.of("ADMINISTRATOR"));
        claims.put("authorities", List.of("ROLE_ADMINISTRATOR"));
        return "Bearer " + TOKENS.token(claims);
    }

    private static ResultMatcher forbidden() {
        return result -> {
            status().isForbidden().match(result);
            errorEnvelope("FORBIDDEN", "Access to this resource is denied.").match(result);
            header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE).match(result);
        };
    }

    private static ResultMatcher unauthorized(String message) {
        return result -> {
            status().isUnauthorized().match(result);
            errorEnvelope("UNAUTHORIZED", message).match(result);
            header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer").match(result);
        };
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

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtClockConfiguration {

        @Bean
        @Primary
        Clock fixedJwtClock() {
            return JwtTestSupport.CLOCK;
        }
    }
}
