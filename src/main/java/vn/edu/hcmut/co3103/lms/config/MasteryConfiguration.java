package vn.edu.hcmut.co3103.lms.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import vn.edu.hcmut.co3103.lms.service.adaptive.MasteryCalculator;
import vn.edu.hcmut.co3103.lms.service.adaptive.TopicQuizEvidenceReader;
import vn.edu.hcmut.co3103.lms.service.adaptive.TopicAssignmentEvidenceReader;

@Configuration(proxyBeanMethods = false)
public class MasteryConfiguration {
    @Bean
    TopicAssignmentEvidenceReader topicAssignmentEvidenceReader(NamedParameterJdbcTemplate jdbc) {
        return new TopicAssignmentEvidenceReader(jdbc);
    }

    @Bean
    MasteryCalculator masteryCalculator() { return new MasteryCalculator(); }

    @Bean
    TopicQuizEvidenceReader topicQuizEvidenceReader(NamedParameterJdbcTemplate jdbc, MasteryCalculator calculator) {
        return new TopicQuizEvidenceReader(jdbc, calculator);
    }
}
