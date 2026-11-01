package vn.edu.hcmut.co3103.lms.config;

import org.apache.catalina.core.StandardHost;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.edu.hcmut.co3103.lms.exception.JsonErrorReportValve;
import vn.edu.hcmut.co3103.lms.exception.AllowedMethods;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TomcatErrorReportConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> jsonContainerErrors(
            @Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappings) {
        // The host reporter also handles rejected URIs that never reach a servlet context.
        return factory -> {
            factory.addConnectorCustomizers(connector -> connector.setAllowTrace(false));
            factory.addContextCustomizers(context -> {
                var host = (StandardHost) context.getParent();
                host.setErrorReportValveClass(null);
                // Resolve MVC lazily: the embedded container is configured before MVC initializes.
                host.getPipeline().addValve(new JsonErrorReportValve(
                        path -> AllowedMethods.forPath(mappings.getObject(), path)));
            });
        };
    }
}
