package vn.edu.hcmut.co3103.lms.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class JsonAuthenticationEntryPointTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonAuthenticationEntryPoint entryPoint = new JsonAuthenticationEntryPoint(objectMapper);

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html", "*/*"})
    void missingAuthenticationReturnsSafeUnauthorizedEnvelope(String accept) throws Exception {
        assertUnauthorized(accept, new InsufficientAuthenticationException("private-exception-details"),
                "A valid Bearer access token is required.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "text/html", "*/*"})
    void invalidTokenReturnsSafeUnauthorizedEnvelope(String accept) throws Exception {
        assertUnauthorized(accept, new OAuth2AuthenticationException(new OAuth2Error("invalid_token"),
                "private-exception-details"), "The Bearer access token is invalid or expired.");
    }

    private void assertUnauthorized(String accept, AuthenticationException exception, String message)
            throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/classes");
        request.setQueryString("token=private-query-value");
        request.addHeader(HttpHeaders.ACCEPT, accept);
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer private-token-value");
        var response = new MockHttpServletResponse();

        entryPoint.commence(request, response, exception);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        var body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.get("code").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("message").asString()).isEqualTo(message);
        assertThat(body.get("details").isArray()).isTrue();
        assertThat(body.get("details").isEmpty()).isTrue();
        assertThat(response.getContentAsString()).doesNotContain("private-", "Exception");
    }
}
