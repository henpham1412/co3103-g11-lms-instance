package vn.edu.hcmut.co3103.lms.service.admin;

import org.junit.jupiter.api.Test;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.repository.admin.AdminReportRepository;
import vn.edu.hcmut.co3103.lms.repository.admin.AdminReportRepository.AssessmentCounts;
import vn.edu.hcmut.co3103.lms.repository.admin.AdminReportRepository.CompletionCounts;
import vn.edu.hcmut.co3103.lms.repository.admin.AdminReportRepository.EnrollmentCounts;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Deterministic LMS-14 rules that cannot be pinned down on a shared database (global empty state, rounding). */
class AdminReportServiceTest {

    private static final String NO_DATA = "No reportable data is available.";

    private final AdminReportRepository repository = mock(AdminReportRepository.class);
    private final ClassRepository classRepository = mock(ClassRepository.class);
    private final AdminReportService service = new AdminReportService(repository, classRepository);

    @Test
    void globalEmptyReportsReturnZeroCountsMessageAndNullAverages() {
        when(repository.enrollment(null)).thenReturn(new EnrollmentCounts(0, 0, 0, 0, 0, 0, 0));
        when(repository.completion(null)).thenReturn(new CompletionCounts(0, 0, 0, 0, BigDecimal.ZERO));
        when(repository.assessment(null)).thenReturn(
                new AssessmentCounts(0, 0, 0, 0, null, 0, 0, 0, 0, null, null));

        var enrollment = service.enrollment(null);
        assertThat(enrollment.reportType()).isEqualTo("enrollment");
        assertThat(enrollment.filters()).isEmpty();
        assertThat(enrollment.message()).isEqualTo(NO_DATA);
        assertThat(enrollment.data().totalMemberships()).isZero();

        var completion = service.completion(null);
        assertThat(completion.message()).isEqualTo(NO_DATA);
        assertThat(completion.data().completionRate()).isEqualByComparingTo("0.00");
        assertThat(completion.data().completionRate().scale()).isEqualTo(2);
        assertThat(completion.data().averageProgressPercentage()).isEqualByComparingTo("0.00");

        var assessment = service.assessment(null);
        assertThat(assessment.message()).isEqualTo(NO_DATA);
        assertThat(assessment.data().averageQuizAttemptScore()).isNull();
        assertThat(assessment.data().averageAssignmentSubmissionScore()).isNull();
        assertThat(assessment.data().averageAssessmentScore()).isNull();
        verify(classRepository, never()).existsById(any());
    }

    @Test
    void enrollmentMessageDependsOnMembershipsNotClasses() {
        when(repository.enrollment(null)).thenReturn(new EnrollmentCounts(2, 2, 0, 0, 0, 0, 0));
        assertThat(service.enrollment(null).message()).isEqualTo(NO_DATA);

        when(repository.enrollment(null)).thenReturn(new EnrollmentCounts(1, 1, 0, 0, 0, 0, 1));
        var report = service.enrollment(null);
        assertThat(report.message()).isNull();
        assertThat(report.data().rejectedRequests()).isEqualTo(1);
        assertThat(report.data().totalMemberships()).isEqualTo(1);
    }

    @Test
    void completionPercentagesUseHalfUpTwoDecimalRounding() {
        // 2 of 3 members completed; progress sum 100 + 100 + 33.333... over 3 members.
        when(repository.completion(null)).thenReturn(
                new CompletionCounts(3, 3, 7, 2, new BigDecimal("233.3333333333333333")));
        var data = service.completion(null).data();
        assertThat(data.completionRate()).isEqualTo(new BigDecimal("66.67"));
        assertThat(data.averageProgressPercentage()).isEqualTo(new BigDecimal("77.78"));
        assertThat(data.inProgressMemberships()).isEqualTo(1);
    }

    @Test
    void assessmentAveragesAreRoundedAndMessageOmittedWhenAnyRecordExists() {
        // 3 graded quizzes (avg 50.00) + 2 graded assignments (avg 75.625) -> weighted avg 60.25 (unweighted would be 62.81).
        when(repository.assessment(null)).thenReturn(new AssessmentCounts(
                4, 1, 3, 3, new BigDecimal("50.0000000000000000"),
                2, 0, 2, 1, new BigDecimal("75.6250000000000000"),
                new BigDecimal("60.2500000000000000")));
        var report = service.assessment(null);
        assertThat(report.message()).isNull();
        assertThat(report.data().averageQuizAttemptScore()).isEqualTo(new BigDecimal("50.00"));
        assertThat(report.data().averageAssignmentSubmissionScore()).isEqualTo(new BigDecimal("75.63"));
        assertThat(report.data().averageAssessmentScore()).isEqualTo(new BigDecimal("60.25"));
    }

    @Test
    void assessmentZeroAverageIsPreservedAndNotTreatedAsNull() {
        when(repository.assessment(null)).thenReturn(new AssessmentCounts(
                1, 0, 1, 1, BigDecimal.ZERO, 0, 0, 0, 0, null, BigDecimal.ZERO));
        var report = service.assessment(null);
        assertThat(report.message()).isNull();
        assertThat(report.data().averageQuizAttemptScore()).isEqualTo(new BigDecimal("0.00"));
        assertThat(report.data().averageAssignmentSubmissionScore()).isNull();
        assertThat(report.data().averageAssessmentScore()).isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void scopedReportRejectsUnknownClassBeforeQuerying() {
        UUID classId = UUID.randomUUID();
        when(classRepository.existsById(classId)).thenReturn(false);
        assertThatThrownBy(() -> service.completion(classId))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CLASS_NOT_FOUND));
        verify(repository, never()).completion(any());
    }

    @Test
    void scopedReportEchoesClassFilter() {
        UUID classId = UUID.randomUUID();
        when(classRepository.existsById(classId)).thenReturn(true);
        when(repository.enrollment(classId)).thenReturn(new EnrollmentCounts(1, 1, 0, 1, 0, 0, 0));
        assertThat(service.enrollment(classId).filters()).containsExactlyEntriesOf(
                java.util.Map.of("class_id", classId.toString()));
    }
}
