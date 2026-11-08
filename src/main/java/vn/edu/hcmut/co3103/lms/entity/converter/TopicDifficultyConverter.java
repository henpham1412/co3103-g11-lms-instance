package vn.edu.hcmut.co3103.lms.entity.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import vn.edu.hcmut.co3103.lms.enums.TopicDifficulty;

@Converter(autoApply = false)
public class TopicDifficultyConverter
        implements AttributeConverter<TopicDifficulty, Short> {

    @Override
    public Short convertToDatabaseColumn(TopicDifficulty attribute) {
        if (attribute == null) {
            return null;
        }

        return switch (attribute) {
            case EASY -> (short) 1;
            case MEDIUM -> (short) 3;
            case HARD -> (short) 5;
        };
    }

    @Override
    public TopicDifficulty convertToEntityAttribute(Short dbData) {
        if (dbData == null) {
            return null;
        }

        return switch (dbData.intValue()) {
            case 1 -> TopicDifficulty.EASY;
            case 3 -> TopicDifficulty.MEDIUM;
            case 5 -> TopicDifficulty.HARD;
            default -> throw new IllegalArgumentException(
                    "Unmapped TopicDifficulty code: " + dbData
            );
        };
    }
}