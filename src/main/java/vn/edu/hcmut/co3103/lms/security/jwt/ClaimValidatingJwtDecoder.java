package vn.edu.hcmut.co3103.lms.security.jwt;

import com.nimbusds.jose.JWSObject;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates raw claims and restores precise timestamps after Nimbus verifies the signed token. */
public final class ClaimValidatingJwtDecoder implements JwtDecoder {

    // Accept epoch seconds from 1970 through 9999, safely below Date's millisecond overflow limit.
    private static final BigDecimal MAX_EPOCH_SECOND_EXCLUSIVE = new BigDecimal("253402300800");
    private static final BigDecimal CLOCK_SKEW_SECONDS = BigDecimal.valueOf(60);
    private static final JsonMapper CLAIMS_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final JwtDecoder delegate;
    private final Clock clock;

    public ClaimValidatingJwtDecoder(NimbusJwtDecoder delegate, Clock clock) {
        this.delegate = delegate;
        this.clock = clock;
        // Nimbus has already truncated NumericDates here. Keep them out of both Jwt construction
        // (which requires exp > iat) and its validators; restore and validate the original values below.
        delegate.setClaimSetConverter(MappedJwtClaimSetConverter.withDefaults(Map.of(
                JwtClaimNames.IAT, ignored -> null,
                JwtClaimNames.EXP, ignored -> null,
                JwtClaimNames.NBF, ignored -> null)));
    }

    @Override
    public Jwt decode(String token) {
        Map<String, Object> claims;
        try {
            // Do not use getJWTClaimsSet(): its seconds-to-milliseconds conversion can overflow.
            // Parse raw literals exactly: Nimbus's JSON parsing can round fractions or underflow to zero.
            claims = CLAIMS_MAPPER.readValue(JWSObject.parse(token).getPayload().toString(),
                    new TypeReference<Map<String, Object>>() {});
        } catch (ParseException | IllegalArgumentException | JacksonException exception) {
            throw new BadJwtException("Malformed JWT claims.", exception);
        }
        if (claims == null) {
            throw new BadJwtException("JWT claims must be a JSON object.");
        }

        BigDecimal expiresAt = validateNumericDate(claims.get(JwtClaimNames.EXP));
        BigDecimal issuedAt = validateNumericDate(claims.get(JwtClaimNames.IAT));
        BigDecimal notBefore = claims.containsKey(JwtClaimNames.NBF)
                ? validateNumericDate(claims.get(JwtClaimNames.NBF)) : null;
        for (String name : List.of("scope", "scp")) {
            if (claims.containsKey(name)) {
                Object value = claims.get(name);
                if (!(value instanceof String)
                        && !(value instanceof List<?> scopes && scopes.stream().allMatch(String.class::isInstance))) {
                    throw new BadJwtException("JWT scopes must be a string or an array of strings.");
                }
            }
        }
        if (claims.containsKey("cnf")) {
            if (!(claims.get("cnf") instanceof Map<?, ?> confirmation)
                    || (confirmation.containsKey("x5t#S256")
                    && !(confirmation.get("x5t#S256") instanceof String))) {
                throw new BadJwtException("Malformed JWT confirmation claim.");
            }
        }

        // Raw checks grant no trust: signature, issuer, subject, type and certificate checks still run.
        Jwt verified = delegate.decode(token);
        Instant now = clock.instant();
        BigDecimal nowSeconds = BigDecimal.valueOf(now.getEpochSecond())
                .add(BigDecimal.valueOf(now.getNano(), 9));
        // Compare original decimals, including sub-nanosecond fractions, before Instant conversion.
        if (expiresAt.compareTo(issuedAt) <= 0) {
            throw new BadJwtException("JWT expiration must be after issuance.");
        }
        if (expiresAt.compareTo(nowSeconds.subtract(CLOCK_SKEW_SECONDS)) < 0
                || issuedAt.compareTo(nowSeconds.add(CLOCK_SKEW_SECONDS)) > 0
                || (notBefore != null && notBefore.compareTo(nowSeconds.add(CLOCK_SKEW_SECONDS)) > 0)) {
            throw new BadJwtException("JWT is outside the allowed time window.");
        }
        Map<String, Object> verifiedClaims = new LinkedHashMap<>(verified.getClaims());
        verifiedClaims.put(JwtClaimNames.IAT, toInstant(issuedAt));
        verifiedClaims.put(JwtClaimNames.EXP, toInstant(expiresAt));
        if (notBefore != null) {
            verifiedClaims.put(JwtClaimNames.NBF, toInstant(notBefore));
        }
        try {
            return Jwt.withTokenValue(token).headers(headers -> headers.putAll(verified.getHeaders()))
                    .claims(converted -> converted.putAll(verifiedClaims)).build();
        } catch (IllegalArgumentException exception) {
            throw new BadJwtException("JWT timestamps cannot be represented as valid Instants.", exception);
        }
    }

    private static BigDecimal validateNumericDate(Object value) {
        if (!(value instanceof Number number)) {
            throw new BadJwtException("JWT timestamps must be NumericDates.");
        }
        BigDecimal seconds = number instanceof BigDecimal decimal ? decimal : new BigDecimal(number.toString());
        if (seconds.signum() < 0 || seconds.compareTo(MAX_EPOCH_SECOND_EXCLUSIVE) >= 0) {
            throw new BadJwtException("JWT timestamp is outside the supported range.");
        }
        return seconds;
    }

    private static Instant toInstant(BigDecimal seconds) {
        long wholeSeconds = seconds.longValue();
        long nanos = seconds.subtract(BigDecimal.valueOf(wholeSeconds)).movePointRight(9).longValue();
        return Instant.ofEpochSecond(wholeSeconds, nanos);
    }
}
