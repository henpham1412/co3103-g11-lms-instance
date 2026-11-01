package vn.edu.hcmut.co3103.lms.config;

import org.apache.catalina.core.StandardHost;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.edu.hcmut.co3103.lms.exception.JsonErrorReportValve;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TomcatErrorReportConfiguration {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> jsonContainerErrors() {
        // The host reporter also handles rejected URIs that never reach a servlet context.
        return factory -> factory.addContextCustomizers(context ->
                ((StandardHost) context.getParent()).setErrorReportValveClass(JsonErrorReportValve.class.getName()));
    }
}
