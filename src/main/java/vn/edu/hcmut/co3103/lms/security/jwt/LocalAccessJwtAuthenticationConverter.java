package vn.edu.hcmut.co3103.lms.security.jwt;

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

import java.util.List;
import java.util.UUID;

@Component
public final class LocalAccessJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final LmsUserAccessRepository repository;
    private final JwtSubjectValidator subjectValidator = new JwtSubjectValidator();

    public LocalAccessJwtAuthenticationConverter(LmsUserAccessRepository repository) {
        this.repository = repository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        var validation = subjectValidator.validate(jwt);
        if (validation.hasErrors()) {
            throw new OAuth2AuthenticationException(validation.getErrors().iterator().next());
        }
        UUID userId = UUID.fromString(jwt.getSubject());
        List<GrantedAuthority> authorities = repository.findById(userId)
                .filter(user -> user.getAccessStatus() == AccessStatus.ACTIVE)
                .map(LmsUserAccess::getLmsRole)
                .map(role -> List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + role.name())))
                .orElseGet(List::of);

        // A valid identity without active local access has no authorities. SecurityConfig
        // requires a local role on every request, producing 403 rather than an authentication failure.
        return new JwtAuthenticationToken(jwt, authorities, userId.toString());
    }
}
