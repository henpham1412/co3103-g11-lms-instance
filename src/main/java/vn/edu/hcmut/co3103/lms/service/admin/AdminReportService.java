package vn.edu.hcmut.co3103.lms.service.admin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.hcmut.co3103.lms.dto.response.AdminReportResponse;
import vn.edu.hcmut.co3103.lms.exception.AppException;
import vn.edu.hcmut.co3103.lms.exception.ErrorCode;
import vn.edu.hcmut.co3103.lms.repository.admin.AdminReportRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

/** LMS-14 basic operational reports (UC7.4): current-state snapshots, optionally scoped to one class. */
@Service
@Transactional(readOnly = true)
public class AdminReportService {

    private static final int SCALE = 2;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AdminReportRepository repository;
    private final ClassRepository classRepository;

    public AdminReportService(AdminReportRepository repository, ClassRepository classRepository) {
        this.repository = repository;
        this.classRepository = classRepository;
    }

    public AdminReportResponse<AdminReportResponse.Enrollment> enrollment(UUID classId) {
        requireClass(classId);
        var counts = repository.enrollment(classId);
        long total = counts.activeMembers() + counts.pendingRequests() + counts.inactiveMembers()
                + counts.rejectedRequests();
        var data = new AdminReportResponse.Enrollment(counts.activeMembers(), counts.pendingRequests(),
                counts.inactiveMembers(), counts.rejectedRequests(), total, counts.totalClasses(),
                counts.activeClasses(), counts.archivedClasses());
        return response("enrollment", classId, total == 0, data);
    }

    public AdminReportResponse<AdminReportResponse.Completion> completion(UUID classId) {
        requireClass(classId);
        var counts = repository.completion(classId);
        long active = counts.activeMembers();
        BigDecimal rate = active == 0 ? ZERO
                : BigDecimal.valueOf(counts.completedMemberships()).multiply(HUNDRED)
                        .divide(BigDecimal.valueOf(active), SCALE, RoundingMode.HALF_UP);
        BigDecimal average = active == 0 || counts.progressPercentageSum() == null ? ZERO
                : counts.progressPercentageSum().divide(BigDecimal.valueOf(active), SCALE, RoundingMode.HALF_UP);
        var data = new AdminReportResponse.Completion(active, counts.eligibleLessons(),
                counts.completedLessonMarks(), counts.completedMemberships(), active - counts.completedMemberships(),
                rate, average);
        return response("completion", classId, active == 0, data);
    }

    public AdminReportResponse<AdminReportResponse.Assessment> assessment(UUID classId) {
        requireClass(classId);
        var counts = repository.assessment(classId);
        long totalGraded = counts.gradedQuizAttempts() + counts.gradedAssignmentSubmissions();
        var data = new AdminReportResponse.Assessment(counts.totalQuizAttempts(), counts.inProgressQuizAttempts(),
                counts.submittedQuizAttempts(), counts.gradedQuizAttempts(),
                average(counts.gradedQuizAttempts(), counts.averageQuizAttemptScore()),
                counts.totalAssignmentSubmissions(), counts.ungradedAssignmentSubmissions(),
                counts.gradedAssignmentSubmissions(), counts.lateAssignmentSubmissions(),
                average(counts.gradedAssignmentSubmissions(), counts.averageAssignmentSubmissionScore()),
                average(totalGraded, counts.averageAssessmentScore()));
        boolean empty = counts.totalQuizAttempts() == 0 && counts.totalAssignmentSubmissions() == 0;
        return response("assessment", classId, empty, data);
    }

    private void requireClass(UUID classId) {
        if (classId != null && !classRepository.existsById(classId)) {
            throw new AppException(ErrorCode.CLASS_NOT_FOUND);
        }
    }

    private static BigDecimal average(long graded, BigDecimal value) {
        return graded == 0 || value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static <T> AdminReportResponse<T> response(String type, UUID classId, boolean empty, T data) {
        Map<String, Object> filters = classId == null ? Map.of() : Map.of("class_id", classId.toString());
        return new AdminReportResponse<>(type, filters, empty ? AdminReportResponse.NO_REPORTABLE_DATA : null, data);
    }
}
