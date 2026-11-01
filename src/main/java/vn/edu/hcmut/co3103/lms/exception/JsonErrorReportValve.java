package vn.edu.hcmut.co3103.lms.exception;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** DOC-05 fallback for container errors that cannot use the servlet error controller. */
public class JsonErrorReportValve extends ErrorReportValve {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        // Preserve Tomcat's reporting lifecycle and any response already produced by the application.
        if (response.isCommitted() || response.getStatus() < 400 || response.getContentWritten() > 0
                || !response.setErrorReported()) {
            return;
        }
        var ioAllowed = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, ioAllowed);
        if (!ioAllowed.get()) {
            return;
        }

        HttpStatus status = HttpStatus.resolve(response.getStatus());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        try {
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            var writer = response.getReporter();
            if (writer != null) {
                // Never include Tomcat's request-derived message, URI, or exception details.
                writer.write(MAPPER.writeValueAsString(Map.of(
                        "code", status.name(),
                        "message", status.is5xxServerError() ? "An unexpected server error occurred."
                                : status.getReasonPhrase(),
                        "details", List.of())));
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException ignored) {
            // As in Tomcat's reporter, the connection may have closed while writing the error.
        }
    }
}
