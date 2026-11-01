package vn.edu.hcmut.co3103.lms.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.math.BigDecimal;
import java.time.Instant;

/** Preserve the decoded token's nanoseconds; TIMESTAMPTZ rounds to microseconds. */
@Converter
public class TokenIssuedAtConverter implements AttributeConverter<Instant, BigDecimal> {

    @Override
    public BigDecimal convertToDatabaseColumn(Instant value) {
        return value == null ? null : BigDecimal.valueOf(value.getEpochSecond())
                .add(BigDecimal.valueOf(value.getNano(), 9));
    }

    @Override
    public Instant convertToEntityAttribute(BigDecimal value) {
        if (value == null) {
            return null;
        }
        long seconds = value.longValue();
        return Instant.ofEpochSecond(seconds, value.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).longValueExact());
    }
}
