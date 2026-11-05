package vn.edu.hcmut.co3103.lms.exception;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("${spring.web.error.path:${error.path:/error}}")
public class JsonErrorController implements ErrorController {

    private final ObjectMapper objectMapper;

    public JsonErrorController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @RequestMapping
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpStatus status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code
                ? HttpStatus.resolve(code) : null;
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }

        // Write the fixed envelope directly so Accept cannot select HTML or suppress the body.
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "code", status.name(),
                "message", status.is5xxServerError() ? "An unexpected server error occurred."
                        : status.getReasonPhrase(),
                "details", List.of()));
    }
}
