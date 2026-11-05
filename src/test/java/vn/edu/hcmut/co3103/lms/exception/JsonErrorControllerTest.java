package vn.edu.hcmut.co3103.lms.exception;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class JsonErrorControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonErrorController controller = new JsonErrorController(objectMapper);

    static Stream<Arguments> errorStatuses() {
        return Stream.of(
                Arguments.of(401, 401, "UNAUTHORIZED", "Unauthorized"),
                Arguments.of(403, 403, "FORBIDDEN", "Forbidden"),
                Arguments.of(404, 404, "NOT_FOUND", "Not Found"),
                Arguments.of(400, 400, "BAD_REQUEST", "Bad Request"),
                Arguments.of(405, 405, "METHOD_NOT_ALLOWED", "Method Not Allowed"),
                Arguments.of(500, 500, "INTERNAL_SERVER_ERROR", "An unexpected server error occurred."),
                Arguments.of(503, 503, "SERVICE_UNAVAILABLE", "An unexpected server error occurred."),
                Arguments.of(999, 500, "INTERNAL_SERVER_ERROR", "An unexpected server error occurred."),
                Arguments.of(null, 500, "INTERNAL_SERVER_ERROR", "An unexpected server error occurred."),
                Arguments.of("401", 500, "INTERNAL_SERVER_ERROR", "An unexpected server error occurred."));
    }

    @ParameterizedTest
    @MethodSource("errorStatuses")
    void writesSafeContractEnvelopeRegardlessOfAccept(Object errorStatus, int status, String code, String message)
            throws Exception {
        for (String accept : new String[] {"application/json", "text/html", "application/json;q=0", "*/*"}) {
            var request = new MockHttpServletRequest("GET", "/error");
            if (errorStatus != null) {
                request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, errorStatus);
            }
            request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/private-original-path");
            request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "private-error-message");
            request.setAttribute(RequestDispatcher.ERROR_EXCEPTION,
                    new IllegalStateException("private-exception-details"));
            request.setQueryString("trace=true&message=true&private=private-query-value");
            request.addHeader(HttpHeaders.ACCEPT, accept);
            var response = new MockHttpServletResponse();

            controller.error(request, response);

            assertThat(response.getStatus()).isEqualTo(status);
            assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
            var body = objectMapper.readTree(response.getContentAsString());
            assertThat(body.size()).isEqualTo(3);
            assertThat(body.get("code").asString()).isEqualTo(code);
            assertThat(body.get("message").asString()).isEqualTo(message);
            assertThat(body.get("details").isArray()).isTrue();
            assertThat(body.get("details").isEmpty()).isTrue();
            assertThat(response.getContentAsString()).doesNotContain("private-", "Exception", "trace");
        }
    }
}
