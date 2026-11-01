package vn.edu.hcmut.co3103.lms.security.jwt;

import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class LocalAccessJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final LmsUserAccessRepository repository;
    private final Validator validator;
    private final Clock clock;
    private final JwtSubjectValidator subjectValidator = new JwtSubjectValidator();

    public LocalAccessJwtAuthenticationConverter(LmsUserAccessRepository repository, Validator validator, Clock clock) {
        this.repository = repository;
        this.validator = validator;
        this.clock = clock;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        var validation = subjectValidator.validate(jwt);
        if (validation.hasErrors()) {
            throw new OAuth2AuthenticationException(validation.getErrors().iterator().next());
        }
        UUID userId = UUID.fromString(jwt.getSubject());
        String fullName = displayClaim(jwt, "fullName");
        String email = displayClaim(jwt, "email");
        var localUser = repository.findById(userId);
        if (localUser.isPresent()) {
            LmsUserAccess user = localUser.get();
            Instant issuedAt = jwt.getIssuedAt();
            boolean newLogin = user.getAccessStatus() == AccessStatus.ACTIVE && issuedAt != null
                    && (user.getLastLoginAt() == null || issuedAt.isAfter(user.getLastLoginAt()));
            if ((fullName != null && !Objects.equals(fullName, user.getFullName()))
                    || (email != null && !Objects.equals(email, user.getEmail())) || newLogin) {
                // Update only projection columns. Re-read authorization after any concurrent access change.
                repository.synchronizeIdentity(userId, fullName, email, issuedAt, clock.instant());
                localUser = repository.findById(userId);
            }
        }
        List<GrantedAuthority> authorities = localUser
                .filter(user -> user.getAccessStatus() == AccessStatus.ACTIVE)
                .map(LmsUserAccess::getLmsRole)
                .map(role -> List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + role.name())))
                .orElseGet(List::of);

        // A valid identity without active local access has no authorities. SecurityConfig
        // requires a local role on ordinary requests, producing 403 rather than an authentication failure.
        return new JwtAuthenticationToken(jwt, authorities, userId.toString());
    }

    private String displayClaim(Jwt jwt, String name) {
        if (!jwt.hasClaim(name)) {
            return null; // Older/minimal identity tokens must not erase the local display copy.
        }
        Object value = jwt.getClaims().get(name);
        if (!(value instanceof String text)
                || !validator.validateValue(LmsUserAccess.class, name, text).isEmpty()) {
            throw new OAuth2AuthenticationException("invalid_token");
        }
        return text;
    }
}
