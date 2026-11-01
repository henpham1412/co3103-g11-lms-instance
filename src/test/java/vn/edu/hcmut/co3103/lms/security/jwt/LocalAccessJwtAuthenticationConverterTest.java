package vn.edu.hcmut.co3103.lms.security.jwt;

import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class LocalAccessJwtAuthenticationConverterTest {

    private static final UUID USER_ID = new UUID(0xabcdef0123456789L, 0xabcdef0123456789L);

    private LmsUserAccessRepository repository;
    private LocalAccessJwtAuthenticationConverter converter;

    @BeforeEach
    void setUp() {
        repository = mock(LmsUserAccessRepository.class);
        converter = new LocalAccessJwtAuthenticationConverter(repository, mock(Validator.class), JwtTestSupport.CLOCK);
    }

    @ParameterizedTest
    @EnumSource(LmsRole.class)
    void grantsOnlyTheActiveDatabaseRoleAndRetainsJwtPrincipal(LmsRole role) {
        when(repository.findById(USER_ID)).thenReturn(Optional.of(user(role, AccessStatus.ACTIVE)));
        Jwt jwt = jwt(USER_ID.toString().toUpperCase(Locale.ROOT));

        JwtAuthenticationToken authentication = (JwtAuthenticationToken) converter.convert(jwt);

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo(USER_ID.toString());
        assertThat(authentication.getPrincipal()).isSameAs(jwt);
        assertThat(authentication.getToken()).isSameAs(jwt);
        assertThat(authentication.getTokenAttributes()).isEqualTo(jwt.getClaims());
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_" + role.name());
        verify(repository).findById(USER_ID);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void grantsNoAuthoritiesForUnknownUserAndDoesNotProvisionAccess() {
        when(repository.findById(USER_ID)).thenReturn(Optional.empty());

        var authentication = converter.convert(jwt(USER_ID.toString()));

        // The identity is valid; the request-level local-role requirement must deny it with 403.
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getAuthorities()).isEmpty();
        verify(repository).findById(USER_ID);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(LmsRole.class)
    void grantsNoAuthoritiesForSuspendedUser(LmsRole role) {
        when(repository.findById(USER_ID)).thenReturn(Optional.of(user(role, AccessStatus.SUSPENDED)));

        assertThat(converter.convert(jwt(USER_ID.toString())).getAuthorities()).isEmpty();
    }

    @Test
    void failsClosedForMissingLocalStatusOrRole() {
        when(repository.findById(USER_ID)).thenReturn(
                Optional.of(user(LmsRole.ADMINISTRATOR, null)),
                Optional.of(user(null, AccessStatus.ACTIVE)));

        assertThat(converter.convert(jwt(USER_ID.toString())).getAuthorities()).isEmpty();
        assertThat(converter.convert(jwt(USER_ID.toString())).getAuthorities()).isEmpty();
    }

    @Test
    void looksUpLocalAccessOnEveryConversionOfTheSameJwt() {
        when(repository.findById(USER_ID)).thenReturn(
                Optional.of(user(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE)),
                Optional.of(user(LmsRole.STUDENT, AccessStatus.ACTIVE)),
                Optional.of(user(LmsRole.STUDENT, AccessStatus.SUSPENDED)),
                Optional.of(user(LmsRole.TUTOR, AccessStatus.ACTIVE)),
                Optional.empty());
        Jwt jwt = jwt(USER_ID.toString());

        assertThat(converter.convert(jwt).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMINISTRATOR");
        assertThat(converter.convert(jwt).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_STUDENT");
        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
        assertThat(converter.convert(jwt).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_TUTOR");
        assertThat(converter.convert(jwt).getAuthorities()).isEmpty();
        verify(repository, times(5)).findById(USER_ID);
        verifyNoMoreInteractions(repository);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "abcdef0123456789abcdef0123456789",
            " abcdef01-2345-6789-abcd-ef0123456789", "abcdef01-2345-6789-abcd-ef0123456789 "})
    void rejectsMalformedSubjectBeforeDatabaseLookup(String subject) {
        assertThatThrownBy(() -> converter.convert(jwt(subject)))
                .isInstanceOf(OAuth2AuthenticationException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsNonStringSubjectBeforeDatabaseLookup() {
        assertThatThrownBy(() -> converter.convert(jwt(List.of(USER_ID.toString()))))
                .isInstanceOf(OAuth2AuthenticationException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void olderTrustedTokenCannotOverwriteNewerIdentityProjection() {
        var user = user(LmsRole.TUTOR, AccessStatus.ACTIVE);
        user.setLastSyncedProfileIat(JwtTestSupport.NOW);
        user.setLastLoginTokenIat(JwtTestSupport.NOW);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(user));
        var oldToken = Jwt.withTokenValue("older-token").header("alg", "RS256").subject(USER_ID.toString())
                .issuedAt(JwtTestSupport.NOW.minusSeconds(30))
                .claim("fullName", "Older Name").claim("email", "older@example.test").build();

        var authentication = converter.convert(oldToken);

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_TUTOR");
        verify(repository).findById(USER_ID);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void consumedFutureIssuedTokenDoesNotSynchronizeAgain() {
        var user = user(LmsRole.STUDENT, AccessStatus.ACTIVE);
        Instant issuedAt = JwtTestSupport.NOW.plusSeconds(45);
        user.setLastLoginAt(JwtTestSupport.NOW);
        user.setLastLoginTokenIat(issuedAt);
        user.setLastSyncedProfileIat(issuedAt);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(user));
        var token = Jwt.withTokenValue("future-token").header("alg", "RS256").subject(USER_ID.toString())
                .issuedAt(issuedAt).claim("email", user.getEmail()).build();

        assertThat(converter.convert(token).isAuthenticated()).isTrue();
        assertThat(converter.convert(token).isAuthenticated()).isTrue();
        verify(repository, times(2)).findById(USER_ID);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void newerUnchangedProfileAdvancesWatermarkEvenForSuspendedUser() {
        var user = user(LmsRole.STUDENT, AccessStatus.SUSPENDED);
        user.setLastSyncedProfileIat(JwtTestSupport.NOW.minusSeconds(30));
        when(repository.findById(USER_ID)).thenReturn(Optional.of(user));
        var token = Jwt.withTokenValue("newer-token").header("alg", "RS256").subject(USER_ID.toString())
                .issuedAt(JwtTestSupport.NOW).claim("email", user.getEmail()).build();

        assertThat(converter.convert(token).getAuthorities()).isEmpty();
        verify(repository).synchronizeIdentity(USER_ID, null, user.getEmail(), JwtTestSupport.NOW, JwtTestSupport.NOW);
        verify(repository, times(2)).findById(USER_ID);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void rereadsAccessAfterConcurrentSuspensionDuringSynchronization() {
        var active = user(LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE);
        var suspended = user(LmsRole.ADMINISTRATOR, AccessStatus.SUSPENDED);
        when(repository.findById(USER_ID)).thenReturn(Optional.of(active), Optional.of(suspended));
        var token = Jwt.withTokenValue("new-token").header("alg", "RS256").subject(USER_ID.toString())
                .issuedAt(JwtTestSupport.NOW).build();

        assertThat(converter.convert(token).getAuthorities()).isEmpty();
        verify(repository).synchronizeIdentity(USER_ID, null, null, JwtTestSupport.NOW, JwtTestSupport.NOW);
        verify(repository, times(2)).findById(USER_ID);
    }

    @Test
    void neverFallsBackToTokenAuthoritiesWhenDatabaseLookupFails() {
        var failure = new DataAccessResourceFailureException("Database unavailable");
        when(repository.findById(USER_ID)).thenThrow(failure);

        assertThatThrownBy(() -> converter.convert(jwt(USER_ID.toString()))).isSameAs(failure);
    }

    private LmsUserAccess user(LmsRole role, AccessStatus status) {
        return new LmsUserAccess(USER_ID, "Local User", "local@example.test", role, status,
                null, null, null);
    }

    private Jwt jwt(Object subject) {
        return Jwt.withTokenValue("test-token").header("alg", "RS256")
                .claim("scope", "STUDENT TUTOR ADMINISTRATOR")
                .claim("scp", List.of("STUDENT", "TUTOR", "ADMINISTRATOR"))
                .claim("roles", List.of("ADMINISTRATOR"))
                .claim("authorities", List.of("ROLE_ADMINISTRATOR"))
                .claim("email", "local@example.test")
                .claims(claims -> {
                    if (subject != null) {
                        claims.put("sub", subject);
                    }
                }).build();
    }
}
