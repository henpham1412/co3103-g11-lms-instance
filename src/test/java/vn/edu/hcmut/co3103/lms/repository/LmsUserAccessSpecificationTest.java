package vn.edu.hcmut.co3103.lms.repository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LmsUserAccessSpecificationTest {

    @Mock private Root<LmsUserAccess> root;
    @Mock private CriteriaBuilder builder;
    @Mock private Path<String> fullName;
    @Mock private Path<String> email;
    @Mock private Expression<String> lowerName;
    @Mock private Expression<String> lowerEmail;
    @Mock private Predicate nameMatch;
    @Mock private Predicate emailMatch;
    @Mock private Predicate searchMatch;
    @Mock private Predicate result;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void ignoresMissingOrBlankQuery(String q) {
        when(builder.and(any(Predicate[].class))).thenReturn(result);
        assertThat(LmsUserAccessSpecification.filter(q, null, null)
                .toPredicate(root, null, builder)).isSameAs(result);

        verify(builder).and(new Predicate[0]);
        verifyNoInteractions(root);
    }

    @ParameterizedTest
    @EnumSource(LmsRole.class)
    void filtersByRoleAlone(LmsRole role) {
        Predicate roleMatch = mock(Predicate.class);
        when(builder.equal(root.get("lmsRole"), role)).thenReturn(roleMatch);

        LmsUserAccessSpecification.filter(null, role, null).toPredicate(root, null, builder);

        verify(builder).and(new Predicate[]{roleMatch});
    }

    @ParameterizedTest
    @EnumSource(AccessStatus.class)
    void filtersByStatusAlone(AccessStatus status) {
        Predicate statusMatch = mock(Predicate.class);
        when(builder.equal(root.get("accessStatus"), status)).thenReturn(statusMatch);

        LmsUserAccessSpecification.filter(null, null, status).toPredicate(root, null, builder);

        verify(builder).and(new Predicate[]{statusMatch});
    }

    @ParameterizedTest
    @ValueSource(strings = {" ALIce ", "alice", "AlIcE"})
    void searchesNameOrEmailIgnoringCaseAndSurroundingWhitespace(String q) {
        stubTextSearch("%alice%");
        when(builder.or(new Predicate[]{nameMatch, emailMatch})).thenReturn(searchMatch);

        LmsUserAccessSpecification.filter(q, null, null).toPredicate(root, null, builder);

        verify(builder).and(new Predicate[]{searchMatch});
        verify(root, never()).get("userId");
    }

    @Test
    void treatsLikeWildcardsAndEscapeCharacterAsLiteralText() {
        stubTextSearch("%a\\%b\\_c\\\\d%");
        when(builder.or(new Predicate[]{nameMatch, emailMatch})).thenReturn(searchMatch);

        LmsUserAccessSpecification.filter("A%B_C\\D", null, null).toPredicate(root, null, builder);

        verify(builder).and(new Predicate[]{searchMatch});
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "123e4567-e89b-12d3-a456-42661417400z"})
    void keepsInvalidUuidQueriesAsTextOnly(String q) {
        stubTextSearch("%" + q + "%");
        when(builder.or(new Predicate[]{nameMatch, emailMatch})).thenReturn(searchMatch);

        LmsUserAccessSpecification.filter(q, null, null).toPredicate(root, null, builder);

        verify(builder).and(new Predicate[]{searchMatch});
        verify(root, never()).get("userId");
    }

    @Test
    void combinesUuidOrTextSearchWithBothRoleAndStatus() {
        UUID userId = new UUID(0xabcdef, 0x123abc);
        stubTextSearch("%" + userId + "%");
        Predicate idMatch = mock(Predicate.class);
        Predicate roleMatch = mock(Predicate.class);
        Predicate statusMatch = mock(Predicate.class);
        when(builder.equal(root.get("userId"), userId)).thenReturn(idMatch);
        when(builder.or(new Predicate[]{nameMatch, emailMatch, idMatch})).thenReturn(searchMatch);
        when(builder.equal(root.get("lmsRole"), LmsRole.ADMINISTRATOR)).thenReturn(roleMatch);
        when(builder.equal(root.get("accessStatus"), AccessStatus.ACTIVE)).thenReturn(statusMatch);

        LmsUserAccessSpecification.filter(" " + userId.toString().toUpperCase(Locale.ROOT) + " ",
                LmsRole.ADMINISTRATOR, AccessStatus.ACTIVE).toPredicate(root, null, builder);

        verify(builder).and(new Predicate[]{searchMatch, roleMatch, statusMatch});
    }

    @Test
    void repositoryExposesSpecificationExecutorAndPrimitiveRoleStatusCount() throws NoSuchMethodException {
        assertThat(JpaSpecificationExecutor.class.isAssignableFrom(LmsUserAccessRepository.class)).isTrue();
        assertThat(LmsUserAccessRepository.class
                .getMethod("countByLmsRoleAndAccessStatus", LmsRole.class, AccessStatus.class)
                .getReturnType()).isEqualTo(long.class);
    }

    private void stubTextSearch(String pattern) {
        when(root.<String>get("fullName")).thenReturn(fullName);
        when(root.<String>get("email")).thenReturn(email);
        when(builder.lower(fullName)).thenReturn(lowerName);
        when(builder.lower(email)).thenReturn(lowerEmail);
        when(builder.like(lowerName, pattern, '\\')).thenReturn(nameMatch);
        when(builder.like(lowerEmail, pattern, '\\')).thenReturn(emailMatch);
    }
}
