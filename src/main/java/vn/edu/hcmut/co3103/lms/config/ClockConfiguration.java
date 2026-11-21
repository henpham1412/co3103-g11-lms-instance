package vn.edu.hcmut.co3103.lms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class ClockConfiguration {
    @Bean
    Clock jwtClock() {
        return Clock.systemUTC();
    }
}
