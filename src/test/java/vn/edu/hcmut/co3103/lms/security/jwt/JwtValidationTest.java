package vn.edu.hcmut.co3103.lms.security.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import vn.edu.hcmut.co3103.lms.config.SecurityConfig;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtValidationTest {

    private static JwtTestSupport tokens;
    private static JwtDecoder decoder;

    @BeforeAll
    static void setUp() throws Exception {
        tokens = new JwtTestSupport();
        decoder = new SecurityConfig().jwtDecoder(JwtTestSupport.ISSUER, tokens.jwksUri(), JwtTestSupport.CLOCK);
    }

    @AfterAll
    static void tearDown() {
        if (tokens != null) {
            tokens.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {JwtTestSupport.SUBJECT, "550E8400-E29B-41D4-A716-446655440000"})
    void acceptsValidRs256Token(String subject) throws Exception {
        Map<String, Object> claims = tokens.claims();
        claims.put("sub", subject);

        Jwt jwt = decoder.decode(tokens.token(claims));

        assertThat(jwt.getSubject()).isEqualTo(subject);
        assertThat(jwt.getExpiresAt()).isEqualTo(Instant.ofEpochSecond((Long) claims.get("exp")));
        assertThat(jwt.getIssuedAt()).isEqualTo(Instant.ofEpochSecond((Long) claims.get("iat")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"exp", "iat", "iss"})
    void rejectsMissingRequiredClaim(String name) throws Exception {
        Map<String, Object> claims = tokens.claims();
        claims.remove(name);
        rejects(claims);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-uuid", "1-1-1-1-1",
            " 550e8400-e29b-41d4-a716-446655440000", "550e8400-e29b-41d4-a716-446655440000 ",
            "550e8400-e29b-41d4-a716-446655440000\n", "550e8400e29b41d4a716446655440000",
            "550e8400-e29b41d4-a716-446655440000", "550e8400-e29b-41d4-a716-44665544000g"})
    void rejectsMissingBlankOrNonCanonicalSubject(String subject) throws Exception {
        Map<String, Object> claims = tokens.claims();
        if (subject == null) {
            claims.remove("sub");
        } else {
            claims.put("sub", subject);
        }
        rejects(claims);
    }

    @ParameterizedTest
    @MethodSource("malformedClaims")
    void rejectsMalformedClaim(String name, Object value) throws Exception {
        Map<String, Object> claims = tokens.claims();
        claims.put(name, value);
        rejects(claims);
    }

    static Stream<Arguments> malformedClaims() {
        return Stream.concat(Stream.of("exp", "iat", "nbf", "sub").flatMap(name -> Stream.of(
                Arguments.of(name, null), Arguments.of(name, true),
                Arguments.of(name, List.of("value")), Arguments.of(name, Map.of("value", "invalid")))),
                Stream.of(Arguments.of("exp", "not-a-date"), Arguments.of("iat", "not-a-date"),
                        Arguments.of("sub", 123)));
    }

    @ParameterizedTest
    @MethodSource("vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport#invalidNumericDates")
    void rejectsNumericDateOverflowAndInvalidRanges(String name, Object value) throws Exception {
        Map<String, Object> claims = tokens.claims();
        claims.put(name, value);
        String token = tokens.token(claims);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @ParameterizedTest
    @MethodSource("vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport#malformedOptionalClaims")
    void rejectsMalformedOptionalClaimsAsBadJwt(String name, Object value) throws Exception {
        Map<String, Object> claims = tokens.claims();
        claims.put(name, value);
        String token = tokens.token(claims);

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @ParameterizedTest
    @MethodSource("vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport#validOptionalClaims")
    void acceptsWellFormedOptionalClaims(String name, Object value) throws Exception {
        Map<String, Object> claims = tokens.claims();
        claims.put(name, value);

        assertThat(decoder.decode(tokens.token(claims)).getClaims()).containsEntry(name, value);
    }

    @Test
    void acceptsNumericDateRangeBoundaries() throws Exception {
        Map<String, Object> claims = tokens.claims();
        claims.put("iat", 0);
        claims.put("nbf", 0);
        claims.put("exp", 253402300799L);

        Jwt jwt = decoder.decode(tokens.token(claims));

        assertThat(jwt.getIssuedAt()).isEqualTo(Instant.EPOCH);
        assertThat(jwt.getNotBefore()).isEqualTo(Instant.EPOCH);
        assertThat(jwt.getExpiresAt()).isEqualTo(Instant.parse("9999-12-31T23:59:59Z"));
    }

    @Test
    void acceptsFractionalNumericDates() throws Exception {
        Map<String, Object> claims = tokens.claims();
        claims.put("iat", ((Long) claims.get("iat")) + 0.5);
        claims.put("exp", ((Long) claims.get("exp")) + 0.5);
        claims.put("nbf", JwtTestSupport.NOW.minusSeconds(30).getEpochSecond() + 0.5);

        assertThat(decoder.decode(tokens.token(claims)).getSubject()).isEqualTo(JwtTestSupport.SUBJECT);
    }

    @ParameterizedTest
    @MethodSource("vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport#validPreciseNumericDates")
    void acceptsPreciseNumericDatesWithinRange(String name, Object value) throws Exception {
        Map<String, Object> claims = tokens.claims();
        claims.put(name, value);

        assertThat(decoder.decode(tokens.token(claims)).getSubject()).isEqualTo(JwtTestSupport.SUBJECT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"exp", "iat", "nbf"})
    void rejectsTimestampOutsideClockSkew(String name) throws Exception {
        Map<String, Object> claims = tokens.claims();
        claims.put(name, JwtTestSupport.NOW.plusSeconds(name.equals("exp") ? -90 : 90).getEpochSecond());
        rejects(claims);
    }

    @ParameterizedTest
    @ValueSource(strings = {"exp", "iat", "nbf"})
    void acceptsTimestampInsideClockSkew(String name) throws Exception {
        Map<String, Object> claims = tokens.claims();
        claims.put(name, JwtTestSupport.NOW.plusSeconds(name.equals("exp") ? -30 : 30).getEpochSecond());
        assertThat(decoder.decode(tokens.token(claims)).getSubject()).isEqualTo(JwtTestSupport.SUBJECT);
    }

    @ParameterizedTest
    @MethodSource("vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport#fractionalTimestampBoundaries")
    void respectsExactClockSkew(String name, String offset, boolean accepted) throws Exception {
        Map<String, Object> claims = tokens.claims();
        claims.put(name, JwtTestSupport.numericDate(JwtTestSupport.NOW).add(new BigDecimal(offset)));
        if (accepted) {
            assertThat(decoder.decode(tokens.token(claims)).getSubject()).isEqualTo(JwtTestSupport.SUBJECT);
        } else {
            rejects(claims);
        }
    }

    @ParameterizedTest
    @MethodSource("vn.edu.hcmut.co3103.lms.security.jwt.JwtTestSupport#fractionalLifespans")
    void validatesSubSecondLifespans(String issuedFraction, String expiryFraction, boolean accepted) throws Exception {
        Map<String, Object> claims = tokens.claims();
        BigDecimal second = BigDecimal.valueOf(JwtTestSupport.NOW.getEpochSecond());
        claims.put("iat", second.add(new BigDecimal(issuedFraction)));
        claims.put("exp", second.add(new BigDecimal(expiryFraction)));
        if (accepted) {
            Jwt jwt = decoder.decode(tokens.token(claims));
            assertThat(jwt.getIssuedAt()).isEqualTo(Instant.ofEpochSecond(second.longValue(),
                    new BigDecimal(issuedFraction).movePointRight(9).longValueExact()));
            assertThat(jwt.getExpiresAt()).isEqualTo(Instant.ofEpochSecond(second.longValue(),
                    new BigDecimal(expiryFraction).movePointRight(9).longValueExact()));
        } else {
            rejects(claims);
        }
    }

    @Test
    void preservesNanosecondsInAllTimestampClaims() throws Exception {
        Map<String, Object> claims = tokens.claims();
        Instant issuedAt = JwtTestSupport.NOW.minusNanos(123456789);
        Instant expiresAt = JwtTestSupport.NOW.plusNanos(234567891);
        Instant notBefore = JwtTestSupport.NOW.minusNanos(98765432);
        claims.put("iat", JwtTestSupport.numericDate(issuedAt));
        claims.put("exp", JwtTestSupport.numericDate(expiresAt));
        claims.put("nbf", JwtTestSupport.numericDate(notBefore));

        Jwt jwt = decoder.decode(tokens.token(claims));

        assertThat(jwt.getIssuedAt()).isEqualTo(issuedAt);
        assertThat(jwt.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(jwt.getNotBefore()).isEqualTo(notBefore);
        assertThat(jwt.getClaims()).containsEntry("iat", issuedAt).containsEntry("exp", expiresAt)
                .containsEntry("nbf", notBefore);
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        Map<String, Object> claims = tokens.claims();
        claims.put("iss", "https://untrusted.example.test");
        rejects(claims);
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        String token = tokens.token(tokens.claims());
        int start = token.lastIndexOf('.') + 1;
        char changed = token.charAt(start) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, start) + changed + token.substring(start + 1);
        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HS256", "RS384", "RS512", "PS256"})
    void rejectsUnsupportedSigningAlgorithm(String algorithm) throws Exception {
        String token = tokens.token(tokens.claims(), JWSAlgorithm.parse(algorithm));
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnsignedToken() throws Exception {
        String token = tokens.unsignedToken();
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private void rejects(Map<String, Object> claims) throws Exception {
        String token = tokens.token(claims);
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
