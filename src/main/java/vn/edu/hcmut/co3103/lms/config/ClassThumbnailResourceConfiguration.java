package vn.edu.hcmut.co3103.lms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

@Configuration
public class ClassThumbnailResourceConfiguration implements WebMvcConfigurer {
    private final Path directory;

    public ClassThumbnailResourceConfiguration(
            @Value("${lms.storage.class-thumbnails.directory:./var/class-thumbnails}") String directory
    ) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/class-thumbnails/**")
                .addResourceLocations(directory.toUri().toString())
                .setCachePeriod(3600);
    }
}
