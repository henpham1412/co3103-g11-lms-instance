package vn.edu.hcmut.co3103.lms.dto.request;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Binds only JSON numbers whose value is exactly an integer, so a fractional payload is rejected
 * instead of being silently truncated to a different capacity than the caller sent.
 */
public class AssessmentIntegerDeserializer extends ValueDeserializer<Integer> {

    private static final BigInteger MIN_INT = BigInteger.valueOf(Integer.MIN_VALUE);
    private static final BigInteger MAX_INT = BigInteger.valueOf(Integer.MAX_VALUE);

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        JsonToken token = parser.currentToken();
        if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) {
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }

        BigDecimal decimal = parser.getDecimalValue();
        if (decimal.stripTrailingZeros().scale() > 0) {
            return (Integer) context.handleWeirdNumberValue(Integer.class, decimal,
                    "is not an integer");
        }
        BigInteger integral = decimal.toBigIntegerExact();
        if (integral.compareTo(MIN_INT) < 0 || integral.compareTo(MAX_INT) > 0) {
            return (Integer) context.handleWeirdNumberValue(Integer.class, decimal,
                    "is outside the accepted integer range");
        }
        return integral.intValue();
    }
}
