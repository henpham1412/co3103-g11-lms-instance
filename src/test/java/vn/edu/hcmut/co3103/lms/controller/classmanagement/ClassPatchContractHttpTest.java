package vn.edu.hcmut.co3103.lms.controller.classmanagement;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real requests and committed PostgreSQL rows: no mocked service or outer test transaction. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ClassPatchContractHttpTest.FixedClockConfiguration.class)
class ClassPatchContractHttpTest {
    private static final JwtTestSupport TOKENS = startTokens();

    private static JwtTestSupport startTokens() {
        try { return new JwtTestSupport(); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> JwtTestSupport.ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TOKENS::jwksUri);
    }

    @AfterAll
    static void closeTokens() { TOKENS.close(); }

    @LocalServerPort private int port;
    @Autowired private ClassRepository classes;
    @Autowired private LmsUserAccessRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    private final HttpClient client = HttpClient.newHttpClient();
    private UUID owner;
    private UUID classId;
    private String token;

    @BeforeEach
    void createCommittedFixtures() throws Exception {
        owner = UUID.randomUUID();
        users.saveAndFlush(new LmsUserAccess(owner, "PATCH Owner", owner + "@example.test",
                LmsRole.TUTOR, AccessStatus.ACTIVE, null, null, null));
        classId = classes.saveAndFlush(new Class(owner, "PATCH-" + UUID.randomUUID().toString().substring(0, 8),
                "Original", "Description", "Prerequisites", 5, Class.EnrollmentPolicy.OPEN)).getClassId();
        var claims = TOKENS.claims();
        claims.put("sub", owner.toString());
        token = TOKENS.token(claims);
    }

    @AfterEach
    void removeFixtures() {
        if (classId != null) classes.deleteById(classId);
        if (owner != null) users.deleteById(owner);
    }

    @ParameterizedTest
    @ValueSource(strings = {"titleProvided", "descriptionProvided", "prerequisitesDescriptionProvided",
            "maxCapacityProvided", "enrollmentPolicyProvided", "statusProvided",
            "title_provided", "description_provided", "prerequisites_description_provided",
            "max_capacity_provided", "enrollment_policy_provided", "status_provided",
            "class_id", "class_code", "tutor_id", "created_at", "updated_at", "unsupported"})
    void internalAndUnsupportedPropertiesAreRejectedEvenAlongsideValidFields(String field) throws Exception {
        assertRejectedWithoutWrites("{\"title\":\"Changed\",\"" + field + "\":true}");
        assertRejectedWithoutWrites("{\"title\":\"Changed\",\"" + field + "\":false}");
        assertRejectedWithoutWrites("{\"title\":\"Changed\",\"" + field + "\":null}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"descriptionProvided\":true}", "{\"maxCapacityProvided\":true}",
            "{\"title\":\"Changed\",\"max_capacity\":0,\"maxCapacityProvided\":false}",
            "{}", "{\"title\":null}", "{\"enrollment_policy\":null}", "{\"status\":null}",
            "{\"max_capacity\":1.5}", "{\"max_capacity\":2.9}"})
    void reviewReproductionsAndInvalidBusinessValuesLeaveAllStoredColumnsUnchanged(String body) throws Exception {
        assertRejectedWithoutWrites(body);
    }

    @Test
    void validPartialUpdatesPreserveOmittedFieldsAndDistinguishEmptyAndNull() throws Exception {
        assertThat(patch("{\"title\":\" Renamed \"}").statusCode()).isEqualTo(200);
        var row = stored();
        assertThat(row.get("title")).isEqualTo("Renamed");
        assertThat(row.get("description")).isEqualTo("Description");
        assertThat(row.get("prerequisites_description")).isEqualTo("Prerequisites");
        assertThat(row.get("max_capacity")).isEqualTo(5);

        assertThat(patch("{\"description\":\"\",\"prerequisites_description\":\"\"}").statusCode()).isEqualTo(200);
        assertThat(stored().get("description")).isEqualTo("");
        assertThat(stored().get("prerequisites_description")).isEqualTo("");
        assertThat(patch("{\"description\":null,\"prerequisites_description\":null,\"max_capacity\":null}")
                .statusCode()).isEqualTo(200);
        row = stored();
        assertThat(row.get("description")).isNull();
        assertThat(row.get("prerequisites_description")).isNull();
        assertThat(row.get("max_capacity")).isNull();
        assertThat(row.get("title")).isEqualTo("Renamed");
        assertThat(patch("{\"enrollment_policy\":\"APPROVAL_REQUIRED\",\"status\":\"ARCHIVED\"}")
                .statusCode()).isEqualTo(200);
        row = stored();
        assertThat(row.get("enrollment_policy")).isEqualTo("APPROVAL_REQUIRED");
        assertThat(row.get("status")).isEqualTo("ARCHIVED");
    }

    private void assertRejectedWithoutWrites(String body) throws Exception {
        var before = stored();
        HttpResponse<String> result = patch(body);
        assertThat(result.statusCode()).as(body).isEqualTo(400);
        assertThat(json.readTree(result.body()).get("code").asString()).isEqualTo("VALIDATION_ERROR");
        assertThat(stored()).as("All columns, including updated_at: " + body).isEqualTo(before);
    }

    private java.util.Map<String, Object> stored() {
        return jdbc.queryForMap("SELECT * FROM class WHERE class_id = ?", classId);
    }

    private HttpResponse<String> patch(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/classes/" + classId))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean @Primary Clock fixedJwtClock() { return JwtTestSupport.CLOCK; }
    }
}
