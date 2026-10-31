package vn.edu.hcmut.co3103.lms.service.classmanagement;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.ClassCreationRequest;
import vn.edu.hcmut.co3103.lms.dto.request.ClassUpdateRequest;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.exception.ClassCodeAlreadyExistsException;
import vn.edu.hcmut.co3103.lms.mapper.ClassMapper;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassCatalogRow;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClassServiceTest {
    private final ClassRepository classRepository = mock(ClassRepository.class);
    private final ClassMemberRepository memberRepository = mock(ClassMemberRepository.class);
    private final ClassMapper mapper = mock(ClassMapper.class);
    private final ClassService service = new ClassService(classRepository, memberRepository, mapper);
    private final UUID classId = UUID.randomUUID();
    private final UUID tutorId = UUID.randomUUID();
    private final UUID otherTutorId = UUID.randomUUID();

    @Test
    void createRejectsDuplicateClassCodeBeforeSaving() {
        ClassCreationRequest request = new ClassCreationRequest();
        request.setClassCode("CS101");
        when(classRepository.existsByClassCode("CS101")).thenReturn(true);

        assertThrows(ClassCodeAlreadyExistsException.class,
                () -> service.createClass(request, tutorId));

        verify(classRepository, never()).save(any(Class.class));
    }

    @Test
    void catalogueNormalizesQueryAndAppliesPolicyAndRequestedPage() {
        PageRequest page = PageRequest.of(1, 12, Sort.by(Sort.Direction.DESC, "created_at"));
        when(classRepository.searchCatalog("Java", "OPEN", page))
                .thenReturn(new PageImpl<>(java.util.List.of(), page, 0));

        var result = service.browseClasses("  Java  ", Class.EnrollmentPolicy.OPEN, page);

        assertEquals(2, result.page());
        assertEquals(12, result.size());
        assertEquals(0, result.totalItems());
        assertEquals(0, result.totalPages());
        verify(classRepository).searchCatalog("Java", "OPEN", page);
    }

    @Test
    void catalogueRejectsSearchLongerThanDocumentedBound() {
        String query = "x".repeat(101);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.browseClasses(query, null, PageRequest.of(0, 12)));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertEquals("INVALID_QUERY_PARAMETER", exception.getReason());
        verify(classRepository, never()).searchCatalog(any(), any(), any());
    }

    @Test
    void tutorCannotUpdateAnotherTutorsClass() {
        Class target = new Class(tutorId, "CS101", "Java", null, null, 10,
                Class.EnrollmentPolicy.OPEN);
        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.of(target));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.updateClass(classId, otherTutorId, new ClassUpdateRequest()));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        verify(classRepository, never()).save(target);
    }

    @Test
    void activeStudentCanSeeArchivedClassDetailsButUnenrolledStudentCannot() {
        UUID studentId = UUID.randomUUID();
        ClassCatalogRow row = mock(ClassCatalogRow.class);
        when(classRepository.findDetailsByClassId(classId)).thenReturn(Optional.of(row));
        when(row.getClassId()).thenReturn(classId);
        when(row.getTutorId()).thenReturn(tutorId);
        when(row.getClassCode()).thenReturn("CS101");
        when(row.getTitle()).thenReturn("Java");
        when(row.getEnrollmentPolicy()).thenReturn("OPEN");
        when(row.getStatus()).thenReturn("ARCHIVED");
        when(row.getTutorName()).thenReturn("Tutor");
        when(memberRepository.existsByClassIdAndStudentIdAndStatus(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(true);

        var details = service.getClassDetails(classId, studentId, false, false);

        assertEquals("ARCHIVED", details.status());
    }

    @Test
    void unenrolledStudentCannotSeeArchivedClassDetails() {
        UUID studentId = UUID.randomUUID();
        ClassCatalogRow row = mock(ClassCatalogRow.class);
        when(classRepository.findDetailsByClassId(classId)).thenReturn(Optional.of(row));
        when(row.getTutorId()).thenReturn(tutorId);
        when(row.getStatus()).thenReturn("ARCHIVED");
        when(memberRepository.existsByClassIdAndStudentIdAndStatus(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(false);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.getClassDetails(classId, studentId, false, false));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        assertEquals("CLASS_NOT_FOUND", exception.getReason());
        verify(memberRepository).existsByClassIdAndStudentIdAndStatus(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE);
    }
}
