package vn.edu.hcmut.co3103.lms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContext;
import vn.edu.hcmut.co3103.lms.bootstrap.AdminBootstrapRunner;
import vn.edu.hcmut.co3103.lms.bootstrap.AdminBootstrapService;

import static org.assertj.core.api.Assertions.assertThat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class Co3103G11LmsInstanceApplicationTests {

    @Test
    void postgresVersionAndConnectionErrorLoggingPolicyMeetDeploymentContract() {
        assertThat(jdbcTemplate.queryForObject("SHOW server_version_num", String.class)).isEqualTo("180006");
        assertThat(jdbcTemplate.queryForObject("SHOW log_error_verbosity", String.class)).isEqualTo("terse");
        assertThat(jdbcTemplate.queryForObject("SHOW log_min_error_statement", String.class)).isEqualTo("panic");
        assertThat(jdbcTemplate.queryForObject("SHOW log_parameter_max_length_on_error", String.class)).isEqualTo("0");
        assertThat(jdbcTemplate.queryForObject("SHOW log_statement", String.class)).isEqualTo("none");
        assertThat(jdbcTemplate.queryForObject("SHOW log_min_duration_statement", String.class)).isEqualTo("-1");
        assertThat(jdbcTemplate.queryForObject("SHOW log_min_duration_sample", String.class)).isEqualTo("-1");
        assertThat(jdbcTemplate.queryForObject("SHOW log_transaction_sample_rate", String.class)).isEqualTo("0");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void ordinaryWebStartupDoesNotExposeOrExecuteBootstrap() {
        assertThat(applicationContext.getBeansOfType(AdminBootstrapRunner.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(AdminBootstrapService.class)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_completed FROM lms_setup_state WHERE id = 'GLOBAL'", Boolean.class)).isFalse();
    }

	@Test
	void contextLoads() {
	}

    @Test
    void protectedEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/classes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointsRejectInvalidBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/classes")
                        .header("Authorization", "Bearer not-a-valid-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void flywayMigrationCreatesLmsUserAccessTable() {
        Integer tableCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'lms_user_access'
                """,
                Integer.class
        );

        assertEquals(1, tableCount);
    }
}
