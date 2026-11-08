package vn.edu.hcmut.co3103.lms.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AllowedMethodsTest {
    @Test
    void discoversChangedMappingsInsteadOfMaintainingAnotherRouteList() throws Exception {
        var mappings = new RequestMappingHandlerMapping();
        var handler = getClass().getDeclaredMethod("handler");
        mappings.registerMapping(RequestMappingInfo.paths("/api/v1/admin/users", "/api/admin/users")
                .methods(RequestMethod.GET).build(), this, handler);
        mappings.registerMapping(RequestMappingInfo.paths("/api/v1/admin/users/{userId}", "/api/admin/users/{userId}")
                .methods(RequestMethod.GET, RequestMethod.PATCH).build(), this, handler);
        for (String prefix : List.of("/api/v1/admin/users", "/api/admin/users")) {
            assertThat(AllowedMethods.forPath(mappings, prefix)).isEqualTo("GET, HEAD, OPTIONS");
            assertThat(AllowedMethods.forPath(mappings, prefix + "/user-id")).isEqualTo("GET, HEAD, PATCH, OPTIONS");
            assertThat(AllowedMethods.forPath(mappings, prefix + "/user-id/unknown")).isEmpty();
        }
        mappings.registerMapping(RequestMappingInfo.paths("/api/v1/admin/users").methods(RequestMethod.POST)
                .build(), this, handler);
        assertThat(AllowedMethods.forPath(mappings, "/api/v1/admin/users")).isEqualTo("GET, HEAD, POST, OPTIONS");
    }

    @Test
    void headIsImplicitOnlyForGetAndTraceIsNeverAdvertised() {
        assertThat(AllowedMethods.header(List.of(HttpMethod.PATCH))).isEqualTo("PATCH, OPTIONS");
        assertThat(AllowedMethods.header(List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE)))
                .isEqualTo("GET, HEAD, OPTIONS");
        assertThat(AllowedMethods.header(List.of())).isEmpty();
    }

    public void handler() {
    }
}
