package vn.edu.hcmut.co3103.lms.controller.adaptive;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import vn.edu.hcmut.co3103.lms.config.SecurityConfig;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.exception.GlobalExceptionHandler;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillTopicService;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual filter configuration; mock decoder/service only, no signature/JWKS or local ACL verification. */
class AdaptiveSecurityFilterTest {
    private static final UUID CLASS_ID = UUID.randomUUID();
    private static final String PATH = "/api/v1/classes/" + CLASS_ID + "/skill-topics";
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private SkillTopicService service;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        service = context.getBean(SkillTopicService.class);
        decoder = context.getBean(JwtDecoder.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void close() { context.close(); }

    @Test
    void missingBearerTokenIs401OutsideMvcAdvice() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andExpect(content().string(""));
        verifyNoInteractions(service, decoder);
    }

    @Test
    void rejectedBearerTokenIs401BeforeController() throws Exception {
        when(decoder.decode("invalid-token")).thenThrow(new BadJwtException("Invalid access token"));
        mvc.perform(get(PATH).header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")));
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedRequestReachesServiceWithoutControllerInventingLocalRoleChecks() throws Exception {
        when(service.listByClass(CLASS_ID)).thenReturn(List.of());
        mvc.perform(get(PATH).with(jwt())).andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(service).listByClass(CLASS_ID);
    }

    @Test
    void authenticatedRequestStillReceivesServiceAccessDenial() throws Exception {
        when(service.listByClass(CLASS_ID)).thenThrow(new AppException(ErrorCode.CLASS_ACCESS_DENIED));
        mvc.perform(get(PATH).with(jwt())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CLASS_ACCESS_DENIED"));
    }

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, SkillTopicController.class, GlobalExceptionHandler.class})
    static class TestConfiguration {
        @Bean
        SkillTopicService skillTopicService() { return mock(SkillTopicService.class); }

        @Bean
        JwtDecoder jwtDecoder() { return mock(JwtDecoder.class); }
    }
}
