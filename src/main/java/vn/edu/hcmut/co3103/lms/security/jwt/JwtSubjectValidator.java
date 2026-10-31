package vn.edu.hcmut.co3103.lms.security.jwt;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

import java.util.regex.Pattern;

public final class JwtSubjectValidator implements OAuth2TokenValidator<Jwt> {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final OAuth2Error ERROR = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN,
            "The sub claim must be a canonical UUID.", null);

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object subject = jwt.getClaims().get(JwtClaimNames.SUB);
        return subject instanceof String value && UUID_PATTERN.matcher(value).matches()
                ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(ERROR);
    }
}
