package vn.edu.hcmut.co3103.lms.exception;

import jakarta.servlet.ServletException;
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
import java.util.function.Function;

/** DOC-05 fallback for container errors that cannot use the servlet error controller. */
public class JsonErrorReportValve extends ErrorReportValve {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final Function<String, String> allowedMethods;

    public JsonErrorReportValve() {
        this(path -> "");
    }

    public JsonErrorReportValve(Function<String, String> allowedMethods) {
        this.allowedMethods = allowedMethods;
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        // Coyote rejects TRACE before the host pipeline. Normalize before /error can commit JSON.
        normalizeAllow(request, response);
        super.invoke(request, response);
    }

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
        normalizeAllow(request, response);
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

    private void normalizeAllow(Request request, Response response) {
        if (response.getStatus() == HttpStatus.METHOD_NOT_ALLOWED.value() && !response.isCommitted()) {
            String path = request.getDecodedRequestURI();
            String contextPath = request.getContextPath();
            if (path != null) {
                if (contextPath != null && !contextPath.isEmpty()) {
                    path = path.substring(contextPath.length());
                }
                response.setHeader("Allow", allowedMethods.apply(path));
            }
        }
    }
}
