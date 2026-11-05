package vn.edu.hcmut.co3103.lms.controller.adaptive;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.edu.hcmut.co3103.lms.entity.SkillTopic;
import vn.edu.hcmut.co3103.lms.enums.TopicDifficulty;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.exception.GlobalExceptionHandler;
import vn.edu.hcmut.co3103.lms.repository.adaptive.*;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillTopicService;
import vn.edu.hcmut.co3103.lms.service.adaptive.integration.ClassAccess;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real DTO/mapper/service with mocked persistence/access boundaries, not database or ACL integration. */
class SkillTopicPatchContractTest {
    private static final UUID CLASS_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TOPIC_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final String PATH = "/api/v1/skill-topics/" + TOPIC_ID;
    private static final Instant CREATED = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-10-02T00:00:00Z");
    private static ValidatorFactory validation;
    private SkillTopicRepository topics;
    private ClassAccess access;
    private SkillTopic topic;
    private MockMvc mvc;

    @BeforeAll
    static void startValidation() { validation = Validation.buildDefaultValidatorFactory(); }

    @AfterAll
    static void closeValidation() { validation.close(); }

    @BeforeEach
    void setUp() {
        topics = mock(SkillTopicRepository.class);
        access = mock(ClassAccess.class);
        topic = spy(new SkillTopic(TOPIC_ID, CLASS_ID, "Original", "Existing description", TopicDifficulty.MEDIUM));
        ReflectionTestUtils.setField(topic, "createdAt", CREATED);
        ReflectionTestUtils.setField(topic, "updatedAt", UPDATED);
        when(topics.findClassIdBySkillTopicId(TOPIC_ID)).thenReturn(Optional.of(CLASS_ID));
        when(topics.findById(TOPIC_ID)).thenReturn(Optional.of(topic));
        when(topics.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var service = new SkillTopicService(topics, mock(SkillPrerequisiteRepository.class),
                mock(LessonSkillTopicRepository.class), mock(ActivitySkillTopicRepository.class),
                mock(QuestionSkillTopicRepository.class), access, validation.getValidator());
        mvc = MockMvcBuilders.standaloneSetup(new SkillTopicController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void nameOnlyPatchPreservesOmittedMetadataAndImmutableFields() throws Exception {
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" Advanced Java \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Advanced Java"))
                .andExpect(jsonPath("$.description").value("Existing description"))
                .andExpect(jsonPath("$.difficulty_level").value("MEDIUM"));
        assertEquals("Advanced Java", topic.getName());
        assertIdentityUnchanged();
        verify(topic, never()).setDescription(any());
        verify(topic, never()).setDifficultyLevel(any());
        verify(topics).existsByClassIdAndNameAndSkillTopicIdNot(CLASS_ID, "Advanced Java", TOPIC_ID);
        verify(topics).saveAndFlush(topic);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"Updated description\""})
    void descriptionOnlyPatchDistinguishesNullEmptyAndText(String jsonValue) throws Exception {
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":" + jsonValue + "}"))
                .andExpect(status().isOk());
        String expected = jsonValue.equals("null") ? null : jsonValue.substring(1, jsonValue.length() - 1);
        assertEquals(expected, topic.getDescription());
        assertEquals("Original", topic.getName());
        assertEquals(TopicDifficulty.MEDIUM, topic.getDifficultyLevel());
        assertIdentityUnchanged();
        verify(topic, never()).setName(any());
        verify(topic, never()).setDifficultyLevel(any());
        verify(topics).saveAndFlush(topic);
    }

    @ParameterizedTest
    @EnumSource(TopicDifficulty.class)
    void difficultyOnlyPatchUsesSymbolicValuesAndPreservesOtherMetadata(TopicDifficulty difficulty) throws Exception {
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"difficulty_level\":\"" + difficulty.name() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.difficulty_level").value(difficulty.name()));
        assertEquals(difficulty, topic.getDifficultyLevel());
        assertEquals("Original", topic.getName());
        assertEquals("Existing description", topic.getDescription());
        assertIdentityUnchanged();
        verify(topic, never()).setName(any());
        verify(topic, never()).setDescription(any());
    }

    @Test
    void nameLimitAppliesAfterNormalization() throws Exception {
        String normalized = "x".repeat(150);
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  " + normalized + "  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value(normalized));
        assertEquals(normalized, topic.getName());
        verify(topics).saveAndFlush(topic);
    }

    @ParameterizedTest
    @MethodSource("invalidPatches")
    void invalidPatchNeverChangesEntityOrAuditFields(String body) throws Exception {
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.details").isArray());
        assertOriginalAndNoWrites();
    }

    static Stream<String> invalidPatches() {
        Stream<String> invalidMetadata = Stream.of("{}", "null", "[]", "{",
                "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\" \\t\\n \"}",
                "{\"name\":\"" + "x".repeat(151) + "\"}",
                "{\"name\":true}", "{\"name\":7}", "{\"name\":[]}", "{\"name\":{}}",
                "{\"description\":false}", "{\"description\":3}", "{\"description\":[]}", "{\"description\":{}}",
                "{\"difficulty_level\":null}",
                "{\"name\":\"Changed\",\"description\":\"Changed\",\"difficulty_level\":null}",
                "{\"description\":\"Changed\",\"name\":null}");
        Stream<String> invalidDifficulty = Stream.of("0", "1", "2", "3", "4", "5", "-1", "1.0",
                        "true", "false", "[]", "{}", "\"3\"", "\"easy\"", "\"EXPERT\"", "\"\"")
                .map(value -> "{\"name\":\"Changed\",\"difficulty_level\":" + value + "}");
        return Stream.concat(invalidMetadata, invalidDifficulty);
    }

    @ParameterizedTest
    @ValueSource(strings = {"skill_topic_id", "class_id", "created_at", "updated_at",
            "nameProvided", "descriptionProvided", "difficultyProvided"})
    void immutableAndPresencePropertiesCannotBeSuppliedByClients(String property) throws Exception {
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Changed\",\"" + property + "\":\"unexpected\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertOriginalAndNoWrites();
    }

    @Test
    void duplicateNormalizedNameReturns409WithoutPartialMutation() throws Exception {
        when(topics.existsByClassIdAndNameAndSkillTopicIdNot(CLASS_ID, "Taken", TOPIC_ID)).thenReturn(true);
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" Taken \",\"description\":null,\"difficulty_level\":\"HARD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKILL_TOPIC_NAME_DUPLICATE"));
        assertOriginalAndNoWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":\" Original \"}", "{\"description\":\"Existing description\"}",
            "{\"difficulty_level\":\"MEDIUM\"}",
            "{\"name\":\"Original\",\"description\":\"Existing description\",\"difficulty_level\":\"MEDIUM\"}"})
    void normalizedNoOpPreservesTimestampWithoutCallingSettersOrPersistence(String body) throws Exception {
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated_at").value(UPDATED.toString()));
        assertOriginalAndNoWrites();
        verify(access).lockForWrite(CLASS_ID);
    }

    @Test
    void clearingAnAlreadyNullDescriptionIsANoOp() throws Exception {
        ReflectionTestUtils.setField(topic, "description", null);
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"description\":null}"))
                .andExpect(status().isOk());
        assertNull(topic.getDescription());
        assertIdentityAndAuditUnchanged();
        verify(topic, never()).setDescription(any());
        verify(topics, never()).saveAndFlush(any());
        verify(topics, never()).flush();
    }

    @Test
    void noOpDoesNotBypassExistingWriteAuthorizationBoundary() throws Exception {
        doThrow(new AppException(ErrorCode.CLASS_ACCESS_DENIED)).when(access).lockForWrite(CLASS_ID);
        mvc.perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Original\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CLASS_ACCESS_DENIED"));
        assertOriginalAndNoWrites();
    }

    @Test
    void otherOperationsKeepTheirExistingInputErrorContract() throws Exception {
        mvc.perform(get("/api/v1/skill-topics/not-a-uuid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/api/v1/classes/" + CLASS_ID + "/skill-topics")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        assertOriginalAndNoWrites();
    }

    private void assertOriginalAndNoWrites() {
        assertEquals("Original", topic.getName());
        assertEquals("Existing description", topic.getDescription());
        assertEquals(TopicDifficulty.MEDIUM, topic.getDifficultyLevel());
        assertIdentityAndAuditUnchanged();
        verify(topic, never()).setName(any());
        verify(topic, never()).setDescription(any());
        verify(topic, never()).setDifficultyLevel(any());
        verify(topics, never()).saveAndFlush(any());
        verify(topics, never()).flush();
        verify(topics, never()).delete(any());
    }

    private void assertIdentityAndAuditUnchanged() {
        assertIdentityUnchanged();
        assertEquals(UPDATED, topic.getUpdatedAt());
    }

    private void assertIdentityUnchanged() {
        assertEquals(TOPIC_ID, topic.getSkillTopicId());
        assertEquals(CLASS_ID, topic.getClassId());
        assertEquals(CREATED, topic.getCreatedAt());
    }
}
