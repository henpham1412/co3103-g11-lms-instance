package vn.edu.hcmut.co3103.lms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@EnableScheduling
public class Co3103G11LmsInstanceApplication {

	public static void main(String[] args) {
		SpringApplication.run(Co3103G11LmsInstanceApplication.class, args);
	}

}
