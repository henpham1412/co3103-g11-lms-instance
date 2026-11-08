package vn.edu.hcmut.co3103.lms.entity.converter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import vn.edu.hcmut.co3103.lms.dto.request.adaptive.CreateSkillTopicRequest;
import vn.edu.hcmut.co3103.lms.enums.TopicDifficulty;

import static org.junit.jupiter.api.Assertions.*;

class TopicDifficultyConverterTest {
    private final TopicDifficultyConverter converter = new TopicDifficultyConverter();
    private final JsonMapper json = JsonMapper.builder().build();

    @ParameterizedTest
    @CsvSource({"EASY,1", "MEDIUM,3", "HARD,5"})
    void symbolicJsonAndExplicitDatabaseCodesRoundTrip(TopicDifficulty difficulty, short code) {
        assertEquals(Short.valueOf(code), converter.convertToDatabaseColumn(difficulty));
        assertEquals(difficulty, converter.convertToEntityAttribute(code));
        assertEquals("\"" + difficulty.name() + "\"", json.writeValueAsString(difficulty));
        assertEquals(difficulty, json.readValue("\"" + difficulty.name() + "\"", TopicDifficulty.class));
        var request = json.readValue("{\"name\":\"Topic\",\"difficulty_level\":\"" + difficulty.name() + "\"}",
                CreateSkillTopicRequest.class);
        assertEquals(difficulty, request.difficultyLevel());
    }

    @ParameterizedTest
    @ValueSource(shorts = {-1, 0, 2, 4, 6, 32767})
    void undefinedDatabaseCodesAreNotRoundedOrInterpretedAsOrdinals(short code) {
        assertThrows(IllegalArgumentException.class, () -> converter.convertToEntityAttribute(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "2", "3", "4", "5", "-1", "1.0", "true", "false", "[]", "{}",
            "\"3\"", "\"easy\"", "\"EXPERT\"", "\"\""})
    void difficultyBindingRejectsNonSymbolicValuesWithoutGlobalMapperConfiguration(String value) {
        assertThrows(JacksonException.class, () -> json.readValue(value, TopicDifficulty.class));
        assertThrows(JacksonException.class, () -> json.readValue(
                "{\"name\":\"Topic\",\"difficulty_level\":" + value + "}", CreateSkillTopicRequest.class));
    }

    @Test
    void converterNullHandlingDoesNotInventADefaultDifficulty() {
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
    }
}
