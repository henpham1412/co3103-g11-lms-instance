package vn.edu.hcmut.co3103.lms.security.jwt;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.provider.Arguments;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Real signatures and an in-process public JWKS endpoint; no external IdP is needed. */
public final class JwtTestSupport implements AutoCloseable {

    public static final String ISSUER = "https://identity.example.test";
    public static final String SUBJECT = "550e8400-e29b-41d4-a716-446655440000";

    public static final Instant NOW = Instant.parse("2030-01-01T00:00:00.500Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final RSAKey key;
    private final HttpServer server;

    public JwtTestSupport() throws JOSEException, IOException {
        key = new RSAKeyGenerator(2048).keyID("test-key").generate();
        byte[] publicKeys = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, publicKeys.length);
                exchange.getResponseBody().write(publicKeys);
            }
        });
        server.start();
    }

    public String jwksUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    public Map<String, Object> claims() {
        long now = NOW.getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("sub", SUBJECT);
        claims.put("iat", now - 300);
        claims.put("exp", now + 300);
        return claims;
    }

    public static Stream<Arguments> invalidNumericDates() {
        // Range and type checks share one implementation; exercise each with a representative claim.
        return Stream.of(
                Arguments.of("exp", 253402300800L),
                Arguments.of("iat", -1L),
                Arguments.of("nbf", -0.5),
                Arguments.of("iat", new BigDecimal("-1e-400")),
                Arguments.of("nbf", new BigDecimal("-1e-400")),
                Arguments.of("iat", new BigDecimal("-1e-10000")),
                Arguments.of("nbf", new BigDecimal("-1e-10000")),
                Arguments.of("exp", new BigDecimal("253402300800.000000000001")),
                Arguments.of("exp", Long.MAX_VALUE),
                Arguments.of("exp", "1700000000"));
    }

    public static BigDecimal numericDate(Instant instant) {
        return BigDecimal.valueOf(instant.getEpochSecond()).add(BigDecimal.valueOf(instant.getNano(), 9));
    }

    public static Stream<Arguments> fractionalTimestampBoundaries() {
        return Stream.of(
                Arguments.of("iat", "60.000000000001", false),
                Arguments.of("iat", "60", true),
                Arguments.of("nbf", "60.000000000001", false),
                Arguments.of("nbf", "60", true),
                Arguments.of("exp", "-60", true),
                Arguments.of("exp", "-60.000000000001", false));
    }

    public static Stream<Arguments> fractionalLifespans() {
        return Stream.of(
                Arguments.of("0.25", "0.75", true),
                Arguments.of("0.5", "0.5", false),
                Arguments.of("0.75", "0.25", false));
    }

    public static Stream<Arguments> malformedOptionalClaims() {
        return Stream.concat(Stream.of("scope", "scp").flatMap(name -> Stream.of(
                Arguments.of(name, 123), Arguments.of(name, null),
                Arguments.of(name, List.of("read", 123)))), Stream.of(
                Arguments.of("cnf", "bad"), Arguments.of("cnf", null),
                Arguments.of("cnf", Map.of("x5t#S256", 123))));
    }

    public static Stream<Arguments> validOptionalClaims() {
        return Stream.concat(Stream.of("scope", "scp").flatMap(name -> Stream.of(
                Arguments.of(name, "read write"), Arguments.of(name, List.of("read", "write")),
                Arguments.of(name, ""), Arguments.of(name, List.of()))),
                Stream.of(Arguments.of("cnf", Map.of())));
    }

    public static Stream<Arguments> unpreservableDisplayClaims() {
        return Stream.of("fullName", "email").flatMap(name -> Stream.of(
                "\u0000name", "na\u0000me", "name\u0000",
                "\uD800name", "na\uD800me", "name\uDBFF",
                "\uDC00name", "na\uDFFFme", "name\uDFFF",
                "name\uDC00\uD800", "name\uD800\uDBFF", "name\uDC00\uDFFF")
                .map(value -> Arguments.of(name, name.equals("email") ? value + "@example.test" : value)));
    }

    public String token(String claimsJson) throws JOSEException {
        return token(new Payload(claimsJson), JWSAlgorithm.RS256);
    }

    public String token(Map<String, Object> claims) throws JOSEException {
        return token(claims, JWSAlgorithm.RS256);
    }

    public String token(Map<String, Object> claims, JWSAlgorithm algorithm) throws JOSEException {
        return token(new Payload(claims), algorithm);
    }

    private String token(Payload payload, JWSAlgorithm algorithm) throws JOSEException {
        JWSObject token = new JWSObject(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).build(),
                payload);
        if (JWSAlgorithm.HS256.equals(algorithm)) {
            // Deliberately use public-key material as an HMAC secret to test algorithm confusion.
            token.sign(new MACSigner(key.toRSAPublicKey().getEncoded()));
        } else {
            token.sign(new RSASSASigner(key));
        }
        return token.serialize();
    }

    public String unsignedToken() throws ParseException {
        return new PlainJWT(JWTClaimsSet.parse(claims())).serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
