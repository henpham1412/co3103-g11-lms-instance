package vn.edu.hcmut.co3103.lms.controller.adaptive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vn.edu.hcmut.co3103.lms.entity.SkillPrerequisite;
import vn.edu.hcmut.co3103.lms.entity.SkillPrerequisiteId;
import vn.edu.hcmut.co3103.lms.entity.SkillTopic;
import vn.edu.hcmut.co3103.lms.enums.TopicDifficulty;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.exception.GlobalExceptionHandler;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillPrerequisiteService;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillTopicAssociationService;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillTopicAssociationService.TopicChange;
import vn.edu.hcmut.co3103.lms.service.adaptive.SkillTopicService;
import vn.edu.hcmut.co3103.lms.service.adaptive.command.CreateSkillTopicCommand;
import vn.edu.hcmut.co3103.lms.service.adaptive.command.UpdateSkillTopicCommand;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Standalone MVC with mocked services: no database, JWT filter or upstream integration claim. */
class AdaptiveControllersTest {
    private static final UUID CLASS_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TOPIC = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_TOPIC = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID RESOURCE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final String TOPIC_PATH = "/api/v1/skill-topics/" + TOPIC;
    private static final String CLASS_PATH = "/api/v1/classes/" + CLASS_ID + "/skill-topics";
    private SkillTopicService topics;
    private SkillPrerequisiteService prerequisites;
    private SkillTopicAssociationService associations;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        topics = mock(SkillTopicService.class);
        prerequisites = mock(SkillPrerequisiteService.class);
        associations = mock(SkillTopicAssociationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SkillTopicController(topics),
                new SkillPrerequisiteController(prerequisites), new SkillTopicAssociationController(associations))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void createMapsRequestToCommandAndReturns201WithLocationAndResponseDto() throws Exception {
        when(topics.create(eq(CLASS_ID), any())).thenReturn(topic());
        mvc.perform(post(CLASS_PATH).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":" Algebra ","description":null,"difficulty_level":"MEDIUM"}
                """))
                .andExpect(status().isCreated()).andExpect(header().string("Location", TOPIC_PATH))
                .andExpect(jsonPath("$.skill_topic_id").value(TOPIC.toString()))
                .andExpect(jsonPath("$.class_id").value(CLASS_ID.toString()))
                .andExpect(jsonPath("$.name").value("Algebra"))
                .andExpect(jsonPath("$.difficulty_level").value("MEDIUM"))
                .andExpect(jsonPath("$.created_at").value("2026-10-30T00:00:00Z"))
                .andExpect(jsonPath("$.updated_at").value("2026-10-30T01:00:00Z"))
                .andExpect(jsonPath("$.skillTopicId").doesNotExist());
        verify(topics).create(CLASS_ID, new CreateSkillTopicCommand(" Algebra ", null, TopicDifficulty.MEDIUM));
    }

    @Test
    void topicReadRoutesMapEntitiesToDtoListsAndDetails() throws Exception {
        when(topics.listByClass(CLASS_ID)).thenReturn(List.of(topic()));
        when(topics.get(TOPIC)).thenReturn(topic());
        mvc.perform(get(CLASS_PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skill_topic_id").value(TOPIC.toString()));
        mvc.perform(get(TOPIC_PATH)).andExpect(status().isOk())
                .andExpect(jsonPath("$.difficulty_level").value("MEDIUM"));
        verify(topics).listByClass(CLASS_ID);
        verify(topics).get(TOPIC);
    }

    @Test
    void deleteDelegatesAndReturns204WithNoBody() throws Exception {
        mvc.perform(delete(TOPIC_PATH)).andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(topics).delete(TOPIC);
    }

    @Test
    void patchMapsMetadataToUpdateCommandAndResponse() throws Exception {
        when(topics.update(eq(TOPIC), any())).thenReturn(topic());
        mvc.perform(patch(TOPIC_PATH).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":" Algebra ","description":"Updated description","difficulty_level":"MEDIUM"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.skill_topic_id").value(TOPIC.toString()));
        verify(topics).update(TOPIC, new UpdateSkillTopicCommand(
                true, " Algebra ", true, "Updated description", true, TopicDifficulty.MEDIUM));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\":\"Algebra\",\"difficulty_level\":\"MEDIUM\"}",
            "{\"name\":\"Algebra\",\"difficulty_level\":\"MEDIUM\",\"description\":null}"})
    void omittedAndNullDescriptionRemainDistinctInTheCommand(String body) throws Exception {
        when(topics.update(eq(TOPIC), any())).thenReturn(topic());
        mvc.perform(patch(TOPIC_PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        verify(topics).update(TOPIC, new UpdateSkillTopicCommand(
                true, "Algebra", body.contains("description"), null, true, TopicDifficulty.MEDIUM));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\" \"}",
            "{\"difficulty_level\":null}"})
    void patchRejectsEmptyOrInvalidSuppliedFields(String body) throws Exception {
        mvc.perform(patch(TOPIC_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(topics);
    }

    @Test
    void prerequisiteRoutesExposeEdgeDtoAndPermitEmptyReplacement() throws Exception {
        when(prerequisites.list(TOPIC)).thenReturn(List.of(
                new SkillPrerequisite(new SkillPrerequisiteId(TOPIC, OTHER_TOPIC))));
        mvc.perform(get(TOPIC_PATH + "/prerequisites")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skill_topic_id").value(TOPIC.toString()))
                .andExpect(jsonPath("$[0].prerequisite_skill_topic_id").value(OTHER_TOPIC.toString()))
                .andExpect(jsonPath("$[0].id").doesNotExist());
        mvc.perform(put(TOPIC_PATH + "/prerequisites").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prerequisite_skill_topic_ids\":[]}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(prerequisites).replace(TOPIC, List.of());
    }

    @Test
    void prerequisiteReplacementPassesUuidListToService() throws Exception {
        mvc.perform(put(TOPIC_PATH + "/prerequisites").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prerequisite_skill_topic_ids\":[\"" + OTHER_TOPIC + "\"]}"))
                .andExpect(status().isNoContent());
        verify(prerequisites).replace(TOPIC, List.of(OTHER_TOPIC));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lessons", "activities"})
    void associationReadsReturnTopicDtos(String kind) throws Exception {
        when(associations.getLessonTopics(RESOURCE)).thenReturn(List.of(topic()));
        when(associations.getActivityTopics(RESOURCE)).thenReturn(List.of(topic()));
        mvc.perform(get("/api/v1/" + kind + "/" + RESOURCE + "/skill-topics"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("Algebra"));
        if (kind.equals("lessons")) verify(associations).getLessonTopics(RESOURCE);
        else verify(associations).getActivityTopics(RESOURCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lessons", "activities"})
    void associationReplacementReturnsExplicitOldNewLists(String kind) throws Exception {
        var change = new TopicChange(Set.of(TOPIC), Set.of(OTHER_TOPIC));
        when(associations.replaceLessonTopics(RESOURCE, List.of(OTHER_TOPIC))).thenReturn(change);
        when(associations.replaceActivityTopics(RESOURCE, List.of(OTHER_TOPIC))).thenReturn(change);
        mvc.perform(put("/api/v1/" + kind + "/" + RESOURCE + "/skill-topics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skill_topic_ids\":[\"" + OTHER_TOPIC + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previous_skill_topic_ids[0]").value(TOPIC.toString()))
                .andExpect(jsonPath("$.current_skill_topic_ids[0]").value(OTHER_TOPIC.toString()));
        if (kind.equals("lessons")) verify(associations).replaceLessonTopics(RESOURCE, List.of(OTHER_TOPIC));
        else verify(associations).replaceActivityTopics(RESOURCE, List.of(OTHER_TOPIC));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lessons", "activities"})
    void emptyAssociationReplacementIsAllowed(String kind) throws Exception {
        when(associations.replaceLessonTopics(RESOURCE, List.of())).thenReturn(new TopicChange(Set.of(TOPIC), Set.of()));
        when(associations.replaceActivityTopics(RESOURCE, List.of())).thenReturn(new TopicChange(Set.of(TOPIC), Set.of()));
        mvc.perform(put("/api/v1/" + kind + "/" + RESOURCE + "/skill-topics")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"skill_topic_ids\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.current_skill_topic_ids").isEmpty());
    }

    @ParameterizedTest
    @MethodSource("invalidCreateBodies")
    void invalidTopicInputIs400BeforeCallingService(String body) throws Exception {
        mvc.perform(post(CLASS_PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(topics);
    }

    static Stream<String> invalidCreateBodies() {
        return Stream.of("{}", "null", "{", "{\"name\":\" \",\"difficulty_level\":\"EASY\"}",
                "{\"name\":\"" + "x".repeat(151) + "\",\"difficulty_level\":\"EASY\"}",
                "{\"name\":\"Valid\",\"difficulty_level\":null}",
                "{\"name\":\"Valid\",\"difficulty_level\":\"EXPERT\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"skill_topic_ids\":null}", "{\"skill_topic_ids\":[null]}",
            "{\"skill_topic_ids\":[\"not-a-uuid\"]}"})
    void invalidAssociationInputIs400BeforeCallingService(String body) throws Exception {
        mvc.perform(put("/api/v1/lessons/" + RESOURCE + "/skill-topics")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(associations);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"prerequisite_skill_topic_ids\":null}", "{\"prerequisite_skill_topic_ids\":[null]}"})
    void invalidPrerequisiteInputIs400BeforeCallingService(String body) throws Exception {
        mvc.perform(put(TOPIC_PATH + "/prerequisites").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(prerequisites);
    }

    @Test
    void malformedPathIdIs400BeforeCallingService() throws Exception {
        mvc.perform(get("/api/v1/skill-topics/not-a-uuid")).andExpect(status().isBadRequest());
        verifyNoInteractions(topics);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLASS_ACCESS_DENIED", "SKILL_TOPIC_NOT_FOUND", "SKILL_TOPIC_REFERENCED"})
    void businessFailuresKeepServiceErrorCodesAndStatus(String value) throws Exception {
        ErrorCode code = ErrorCode.valueOf(value);
        doThrow(new AppException(code)).when(topics).delete(TOPIC);
        mvc.perform(delete(TOPIC_PATH)).andExpect(status().is(code.getStatus().value()))
                .andExpect(jsonPath("$.code").value(value));
    }

    @Test
    void quizWriteRejectionIsOwnedByServiceAndReturnedAs409() throws Exception {
        when(associations.replaceActivityTopics(RESOURCE, List.of())).thenThrow(new AppException(ErrorCode.QUIZ_TOPICS_DERIVED));
        mvc.perform(put("/api/v1/activities/" + RESOURCE + "/skill-topics")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"skill_topic_ids\":[]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUIZ_TOPICS_DERIVED"));
    }

    @Test
    void noQuestionWriteEndpointIsRegistered() throws Exception {
        mvc.perform(put("/api/v1/questions/" + RESOURCE + "/skill-topics")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"skill_topic_ids\":[]}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(topics, prerequisites, associations);
    }

    private SkillTopic topic() {
        SkillTopic topic = new SkillTopic(TOPIC, CLASS_ID, "Algebra", null, TopicDifficulty.MEDIUM);
        ReflectionTestUtils.setField(topic, "createdAt", Instant.parse("2026-10-30T00:00:00Z"));
        ReflectionTestUtils.setField(topic, "updatedAt", Instant.parse("2026-10-30T01:00:00Z"));
        return topic;
    }
}
