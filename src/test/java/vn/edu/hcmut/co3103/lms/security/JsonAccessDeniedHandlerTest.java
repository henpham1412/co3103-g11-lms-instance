package vn.edu.hcmut.co3103.lms.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class JsonAccessDeniedHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonAccessDeniedHandler handler = new JsonAccessDeniedHandler(objectMapper);

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html", "*/*"})
    void writesSafeForbiddenEnvelope(String accept) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/classes");
        request.setQueryString("token=private-query-value");
        request.addHeader(HttpHeaders.ACCEPT, accept);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer private-token-value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException(
                "Sensitive user or SQL details", new IllegalStateException("private-cause")));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(body.get("message").asString()).isEqualTo("Access to this resource is denied.");
        assertThat(body.get("details").isArray()).isTrue();
        assertThat(body.get("details").isEmpty()).isTrue();
        assertThat(response.getContentAsString()).doesNotContain("private-", "Sensitive", "SQL",
                "AccessDeniedException", "IllegalStateException");
    }
}
