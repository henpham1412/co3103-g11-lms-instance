package vn.edu.hcmut.co3103.lms.controller.progress;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import vn.edu.hcmut.co3103.lms.dto.request.LastVisitRequest;
import vn.edu.hcmut.co3103.lms.dto.response.ClassProgressResponse;
import vn.edu.hcmut.co3103.lms.dto.response.CompletionMarkResponse;
import vn.edu.hcmut.co3103.lms.dto.response.LastVisitResponse;
import vn.edu.hcmut.co3103.lms.dto.response.PageResponse;
import vn.edu.hcmut.co3103.lms.dto.response.AssessmentHistoryItem;
import vn.edu.hcmut.co3103.lms.dto.response.TutorClassProgressResponse;
import vn.edu.hcmut.co3103.lms.dto.response.TutorMemberProgressResponse;
import vn.edu.hcmut.co3103.lms.service.progress.AssessmentHistoryService;
import vn.edu.hcmut.co3103.lms.service.progress.TutorProgressService;
import vn.edu.hcmut.co3103.lms.service.progress.LastVisitService;
import vn.edu.hcmut.co3103.lms.service.progress.LessonCompletionService;
import vn.edu.hcmut.co3103.lms.service.progress.StudentProgressService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Lms7ProgressEndpointsTest {
    private final UUID classId = UUID.randomUUID();
    private final UUID lessonId = UUID.randomUUID();
    private final UUID studentId = UUID.randomUUID();
    private final Jwt jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject(studentId.toString())
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();

    @Test
    void getProgressReturnsServiceProgress() {
        StudentProgressService service = mock(StudentProgressService.class);
        StudentProgressController controller = new StudentProgressController(service);
        var response = new ClassProgressResponse(classId, 1, 2, 50, "IN_PROGRESS", List.of(),
                new ClassProgressResponse.ResumeTarget("LESSON", lessonId, "FIRST_INCOMPLETE_LESSON"));
        when(service.getClassProgress(classId, studentId)).thenReturn(response);

        assertEquals(response, controller.getClassProgress(jwt, classId));
        verify(service).getClassProgress(classId, studentId);
    }

    @Test
    void resumeTargetReturnsServiceSelection() {
        StudentProgressService service = mock(StudentProgressService.class);
        StudentProgressController controller = new StudentProgressController(service);
        var response = new ClassProgressResponse.ResumeTarget("QUIZ_ATTEMPT", lessonId, "IN_PROGRESS_ATTEMPT");
        when(service.getResumeTarget(classId, studentId)).thenReturn(response);

        assertEquals(response, controller.getResumeTarget(jwt, classId));
        verify(service).getResumeTarget(classId, studentId);
    }

    @Test
    void putCompletionReturns200AndRefreshedMark() {
        LessonCompletionService service = mock(LessonCompletionService.class);
        LessonCompletionController controller = new LessonCompletionController(service);
        var response = new CompletionMarkResponse(lessonId, true, Instant.now());
        when(service.markComplete(classId, lessonId, studentId)).thenReturn(response);

        var result = controller.markComplete(jwt, classId, lessonId);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(response, result.getBody());
        verify(service).markComplete(classId, lessonId, studentId);
    }

    @Test
    void deleteCompletionReturns200AndUnmarkedState() {
        LessonCompletionService service = mock(LessonCompletionService.class);
        LessonCompletionController controller = new LessonCompletionController(service);
        var response = CompletionMarkResponse.notCompleted(lessonId);
        when(service.markIncomplete(classId, lessonId, studentId)).thenReturn(response);

        var result = controller.markIncomplete(jwt, classId, lessonId);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(response, result.getBody());
        verify(service).markIncomplete(classId, lessonId, studentId);
    }

    @Test
    void putLastVisitReturnsUpdatedVisit() {
        LastVisitService service = mock(LastVisitService.class);
        LastVisitController controller = new LastVisitController(service);
        var request = new LastVisitRequest(lessonId);
        var response = new LastVisitResponse(lessonId, Instant.now());
        when(service.recordVisit(classId, lessonId, studentId)).thenReturn(response);

        var result = controller.recordVisit(jwt, classId, request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(response, result.getBody());
        verify(service).recordVisit(classId, lessonId, studentId);
    }

    @Test
    void assessmentHistoryReturnsPaginatedQuizAttemptsForCurrentStudent() {
        AssessmentHistoryService service = mock(AssessmentHistoryService.class);
        AssessmentHistoryController controller = new AssessmentHistoryController(service);
        var item = new AssessmentHistoryItem(UUID.randomUUID(), classId, UUID.randomUUID(), "Quiz 1",
                1, "QUIZ", "SUBMITTED", Instant.now().atOffset(java.time.ZoneOffset.UTC), null,
                null, null);
        var response = new PageResponse<>(List.of(item), 1, 20, 1, 1);
        when(service.getHistory(studentId, classId, "QUIZ", 1, 20)).thenReturn(response);

        assertEquals(response, controller.getHistory(jwt, classId, "QUIZ", 1, 20));
        verify(service).getHistory(studentId, classId, "QUIZ", 1, 20);
    }

    @Test
    void tutorClassProgressReturnsActiveMemberSummaries() {
        TutorProgressService service = mock(TutorProgressService.class);
        TutorProgressController controller = new TutorProgressController(service);
        var response = new TutorClassProgressResponse(classId, List.of());
        when(service.getClassProgress(classId, studentId)).thenReturn(response);

        assertEquals(response, controller.getClassProgress(jwt, classId));
        verify(service).getClassProgress(classId, studentId);
    }

    @Test
    void tutorMemberProgressReturnsIndividualHistory() {
        TutorProgressService service = mock(TutorProgressService.class);
        TutorProgressController controller = new TutorProgressController(service);
        UUID memberId = UUID.randomUUID();
        var response = new TutorMemberProgressResponse(classId, memberId, studentId, "ACTIVE",
                1, 1, 100, "COMPLETED", List.of(), List.of(), List.of());
        when(service.getMemberProgress(classId, memberId, studentId)).thenReturn(response);

        assertEquals(response, controller.getMemberProgress(jwt, classId, memberId));
        verify(service).getMemberProgress(classId, memberId, studentId);
    }
}
