package vn.edu.hcmut.co3103.lms.exception;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Resource method discovery is independent of CORS and representation negotiation. */
public final class AllowedMethods {
    private AllowedMethods() {
    }

    public static String header(Collection<HttpMethod> mapped) {
        Set<HttpMethod> methods = new LinkedHashSet<>(mapped);
        if (methods.isEmpty()) {
            return "";
        }
        if (methods.contains(HttpMethod.GET)) {
            methods.add(HttpMethod.HEAD);
        }
        methods.add(HttpMethod.OPTIONS);
        // TRACE remains disabled at the connector even if a servlet advertises it.
        return List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT,
                        HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.OPTIONS).stream()
                .filter(methods::contains).map(HttpMethod::name).collect(Collectors.joining(", "));
    }

    public static String forPath(RequestMappingHandlerMapping mappings, String path) {
        var parsed = PathContainer.parsePath(path);
        Set<HttpMethod> methods = new LinkedHashSet<>();
        for (var mapping : mappings.getHandlerMethods().keySet()) {
            var paths = mapping.getPathPatternsCondition();
            if (paths != null && paths.getPatterns().stream().anyMatch(pattern -> pattern.matches(parsed))) {
                var declared = mapping.getMethodsCondition().getMethods();
                if (declared.isEmpty()) {
                    methods.addAll(List.of(HttpMethod.values()));
                } else {
                    declared.forEach(method -> methods.add(HttpMethod.valueOf(method.name())));
                }
            }
        }
        return header(methods);
    }
}
