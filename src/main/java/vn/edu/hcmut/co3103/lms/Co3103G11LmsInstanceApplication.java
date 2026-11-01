package vn.edu.hcmut.co3103.lms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class Co3103G11LmsInstanceApplication {

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(Co3103G11LmsInstanceApplication.class);
		ConfigurableApplicationContext context = application.run(args);
		if (application.getWebApplicationType() == WebApplicationType.NONE) {
			context.close();
		}
	}

}
