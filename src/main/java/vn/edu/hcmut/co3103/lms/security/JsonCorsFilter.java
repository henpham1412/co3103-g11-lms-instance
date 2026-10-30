package vn.edu.hcmut.co3103.lms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.CorsFilter;

import java.io.IOException;

/** Handles CORS and OPTIONS before authentication, with the standard JSON denial response. */
public final class JsonCorsFilter extends CorsFilter {

    public JsonCorsFilter(CorsConfigurationSource source, JsonAccessDeniedHandler deniedHandler) {
        super(source);
        var processor = new DefaultCorsProcessor() {
            @Override
            protected void rejectRequest(ServerHttpResponse response) {
                // Leave the response uncommitted so the handler below can write the JSON envelope.
                response.setStatusCode(HttpStatus.FORBIDDEN);
            }
        };
        setCorsProcessor((configuration, request, response) -> {
            boolean allowed = processor.processRequest(configuration, request, response);
            if (!allowed) {
                deniedHandler.handle(request, response, new AccessDeniedException("CORS request denied."));
            }
            return allowed;
        });
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        super.doFilterInternal(request, response, (corsRequest, corsResponse) -> {
            // The CORS whitelist has already been enforced. OPTIONS performs no business operation.
            if (HttpMethod.OPTIONS.matches(request.getMethod())) {
                response.setStatus(HttpServletResponse.SC_OK);
            } else {
                chain.doFilter(corsRequest, corsResponse);
            }
        });
    }
}
