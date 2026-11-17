package vn.edu.hcmut.co3103.lms.exception;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.coyote.ActionCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JsonErrorReportValveTest {
    private final JsonErrorReportValve valve = new JsonErrorReportValve();
    private final Request request = mock(Request.class);
    private final Response response = mock(Response.class);

    @ParameterizedTest
    @CsvSource({
            "400, BAD_REQUEST, Bad Request",
            "404, NOT_FOUND, Not Found",
            "500, INTERNAL_SERVER_ERROR, An unexpected server error occurred.",
            "503, SERVICE_UNAVAILABLE, An unexpected server error occurred.",
            "599, INTERNAL_SERVER_ERROR, An unexpected server error occurred."
    })
    void reportsOnlySafeStatusMetadataWithoutChangingStatus(int status, String code, String message) throws Exception {
        when(response.getStatus()).thenReturn(status);
        when(response.setErrorReported()).thenReturn(true);
        allowIo(true);
        var writer = new StringWriter();
        when(response.getReporter()).thenReturn(new PrintWriter(writer));

        valve.report(request, response, new IllegalArgumentException("private-uri-and-database-details"));

        var body = JsonMapper.builder().build().readTree(writer.toString());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isEqualTo(message);
        assertThat(body.path("details").isArray()).isTrue();
        assertThat(body.path("details").isEmpty()).isTrue();
        verify(response).setContentType("application/json");
        verify(response).setCharacterEncoding("UTF-8");
        verify(response).finishResponse();
        verify(response, never()).setStatus(anyInt());
        verify(response, never()).getMessage();
    }

    @ParameterizedTest
    @ValueSource(strings = {"successful", "committed", "body-written", "already-reported"})
    void leavesExistingResponsesUntouched(String state) throws Exception {
        when(response.getStatus()).thenReturn(400);
        when(response.setErrorReported()).thenReturn(true);
        switch (state) {
            case "successful" -> when(response.getStatus()).thenReturn(200);
            case "committed" -> when(response.isCommitted()).thenReturn(true);
            case "body-written" -> when(response.getContentWritten()).thenReturn(64L);
            case "already-reported" -> when(response.setErrorReported()).thenReturn(false);
            default -> throw new AssertionError(state);
        }

        valve.report(request, response, null);

        verify(response, never()).getReporter();
        verify(response, never()).setContentType(any());
        verify(response, never()).finishResponse();
    }

    @Test
    void doesNotWriteWhenConnectionDisallowsIo() throws Exception {
        when(response.getStatus()).thenReturn(400);
        when(response.setErrorReported()).thenReturn(true);
        allowIo(false);

        valve.report(request, response, null);

        verify(response, never()).getReporter();
        verify(response, never()).finishResponse();
    }

    private void allowIo(boolean allowed) {
        var coyote = mock(org.apache.coyote.Response.class);
        when(response.getCoyoteResponse()).thenReturn(coyote);
        doAnswer(invocation -> {
            invocation.getArgument(1, AtomicBoolean.class).set(allowed);
            return null;
        }).when(coyote).action(eq(ActionCode.IS_IO_ALLOWED), any());
    }
}
