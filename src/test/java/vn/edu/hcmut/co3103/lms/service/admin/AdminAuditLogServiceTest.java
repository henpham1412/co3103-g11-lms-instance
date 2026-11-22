package vn.edu.hcmut.co3103.lms.service.admin;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.AuditLog;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.repository.AuditLogRepository;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditLogServiceTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final AdminAuditLogService service = new AdminAuditLogService(repository, objectMapper);

    @Test
    void getParsesBothKnownLegitimateAuditSchemas() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID userLogId = UUID.randomUUID();
        LmsUserAccess target = new LmsUserAccess(targetId, "Student", "s@example.test",
                LmsRole.TUTOR, AccessStatus.SUSPENDED, null, null, null);
        AuditLog userEntry = new AuditLog(actorId, target, LmsRole.STUDENT, AccessStatus.ACTIVE);
        setField(userEntry, "auditLogId", userLogId);
        when(repository.findById(userLogId)).thenReturn(Optional.of(userEntry));

        var userResponse = service.get(userLogId);
        assertThat(userResponse.auditLogId()).isEqualTo(userLogId);
        assertThat(userResponse.actorId()).isEqualTo(actorId);
        assertThat(userResponse.action()).isEqualTo("LMS_USER_ACCESS_UPDATED");
        assertThat(userResponse.entityType()).isEqualTo("LmsUserAccess");
        assertThat(userResponse.entityId()).isEqualTo(targetId);
        assertThat(userResponse.details().isObject()).isTrue();
        assertThat(userResponse.details().get("before").get("lms_role").asText()).isEqualTo("STUDENT");
        assertThat(userResponse.details().get("before").get("access_status").asText()).isEqualTo("ACTIVE");
        assertThat(userResponse.details().get("after").get("lms_role").asText()).isEqualTo("TUTOR");
        assertThat(userResponse.details().get("after").get("access_status").asText()).isEqualTo("SUSPENDED");

        UUID reportId = UUID.randomUUID();
        UUID reportLogId = UUID.randomUUID();
        AuditLog reportEntry = AuditLog.contentReportReviewed(actorId, reportId,
                vn.edu.hcmut.co3103.lms.entity.ContentReport.ReportStatus.PENDING,
                vn.edu.hcmut.co3103.lms.entity.ContentReport.ReportStatus.RESOLVED);
        setField(reportEntry, "auditLogId", reportLogId);
        when(repository.findById(reportLogId)).thenReturn(Optional.of(reportEntry));

        var reportResponse = service.get(reportLogId);
        assertThat(reportResponse.action()).isEqualTo("REPORTED_CONTENT_REVIEWED");
        assertThat(reportResponse.details().get("before").get("status").asText()).isEqualTo("PENDING");
        assertThat(reportResponse.details().get("after").get("status").asText()).isEqualTo("RESOLVED");
    }

    @Test
    void malformedOrNonObjectLegacyDetailsUseNeutralFallbackWithoutLeakingRawValue() throws Exception {
        for (String raw : List.of(
                "not-json synthetic_marker_1",
                "\"synthetic_marker_2\"",
                "[1, 2, 3]",
                "42",
                "   ")) {
            UUID logId = UUID.randomUUID();
            AuditLog entry = auditWithRawDetails(logId, "LMS_USER_ACCESS_UPDATED", raw);
            when(repository.findById(logId)).thenReturn(Optional.of(entry));

            var response = service.get(logId);
            assertThat(response.details().isObject()).isTrue();
            assertThat(response.details().size()).isEqualTo(1);
            assertThat(response.details().get("unavailable").asBoolean()).isTrue();
            assertThat(response.details().toString()).doesNotContain("synthetic_marker_1", "synthetic_marker_2");
        }
    }

    @Test
    void sensitiveOrUnsupportedJsonObjectsReturnNeutralFallback() throws Exception {
        record Case(String action, String json) { }
        List<Case> cases = List.of(
                // Valid JSON with sensitive credential-like keys
                new Case("LMS_USER_ACCESS_UPDATED",
                        "{\"password\":\"SYNTH_PW\",\"access_token\":\"SYNTH_AT\",\"refresh_token\":\"SYNTH_RT\"}"),
                // Nested unexpected sensitive field inside before/after
                new Case("LMS_USER_ACCESS_UPDATED",
                        "{\"before\":{\"lms_role\":\"STUDENT\",\"access_status\":\"ACTIVE\",\"password\":\"SYNTH_PW\"},"
                                + "\"after\":{\"lms_role\":\"TUTOR\",\"access_status\":\"ACTIVE\"}}"),
                // Extra root field on known action
                new Case("LMS_USER_ACCESS_UPDATED",
                        "{\"before\":{\"lms_role\":\"STUDENT\",\"access_status\":\"ACTIVE\"},"
                                + "\"after\":{\"lms_role\":\"TUTOR\",\"access_status\":\"ACTIVE\"},\"extra\":\"SYNTH_EXTRA\"}"),
                // Missing required field (access_status omitted)
                new Case("LMS_USER_ACCESS_UPDATED",
                        "{\"before\":{\"lms_role\":\"STUDENT\"},\"after\":{\"lms_role\":\"TUTOR\"}}"),
                // Invalid enum value
                new Case("LMS_USER_ACCESS_UPDATED",
                        "{\"before\":{\"lms_role\":\"SUPERADMIN\",\"access_status\":\"ACTIVE\"},"
                                + "\"after\":{\"lms_role\":\"TUTOR\",\"access_status\":\"ACTIVE\"}}"),
                // Nested extra field on content report review
                new Case("REPORTED_CONTENT_REVIEWED",
                        "{\"before\":{\"status\":\"PENDING\",\"access_token\":\"SYNTH_AT\"},"
                                + "\"after\":{\"status\":\"RESOLVED\"}}"),
                // Invalid ReportStatus enum
                new Case("REPORTED_CONTENT_REVIEWED",
                        "{\"before\":{\"status\":\"PENDING\"},\"after\":{\"status\":\"DELETED\"}}"),
                // Unknown action type even with valid JSON
                new Case("UNKNOWN_FUTURE_ACTION",
                        "{\"before\":{\"status\":\"PENDING\"},\"after\":{\"status\":\"RESOLVED\"}}")
        );
        for (Case c : cases) {
            UUID logId = UUID.randomUUID();
            AuditLog entry = auditWithRawDetails(logId, c.action(), c.json());
            when(repository.findById(logId)).thenReturn(Optional.of(entry));

            var response = service.get(logId);
            assertThat(response.details().isObject()).isTrue();
            assertThat(response.details().size()).isEqualTo(1);
            assertThat(response.details().get("unavailable").asBoolean()).isTrue();
            assertThat(response.details().toString())
                    .doesNotContain("SYNTH_PW", "SYNTH_AT", "SYNTH_RT", "SYNTH_EXTRA");
        }
    }

    @Test
    void getThrowsAuditLogNotFoundWhenMissing() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(missing))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AUDIT_LOG_NOT_FOUND));
    }

    @Test
    void listRejectsInvalidDateRangeAndPaginationBeforeQuerying() {
        Instant t1 = Instant.parse("2026-10-15T00:00:00Z");
        Instant t0 = Instant.parse("2026-10-14T00:00:00Z");

        assertThatThrownBy(() -> service.list(null, null, null, t1, t0, 1, 20))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.list(null, null, null, null, null, 0, 20))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.list(null, null, null, null, null, 1, 101))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        assertThatThrownBy(() -> service.list(null, null, null, null, null, Integer.MAX_VALUE, 20))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verify(repository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReturnsEmptyPageResponseWhenNoRecordsMatch() {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        var page = service.list(null, null, null, null, null, 1, 20);
        assertThat(page.items()).isEmpty();
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalItems()).isZero();
        assertThat(page.totalPages()).isZero();
    }

    private static AuditLog auditWithRawDetails(UUID logId, String action, String rawDetails) throws Exception {
        var ctor = AuditLog.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        AuditLog entry = ctor.newInstance();
        setField(entry, "auditLogId", logId);
        setField(entry, "actorId", UUID.randomUUID());
        setField(entry, "action", action);
        setField(entry, "entityType", "LmsUserAccess");
        setField(entry, "entityId", UUID.randomUUID());
        setField(entry, "details", rawDetails);
        setField(entry, "createdAt", Instant.parse("2026-10-14T09:30:00Z"));
        return entry;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
