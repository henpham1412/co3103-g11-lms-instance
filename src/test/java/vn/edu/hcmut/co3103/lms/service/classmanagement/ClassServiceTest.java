package vn.edu.hcmut.co3103.lms.service.classmanagement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassDetailRow;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;

import java.util.List;
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
    private final ClassRepository classes = mock(ClassRepository.class);
    private final ClassMemberRepository members = mock(ClassMemberRepository.class);
    private final ClassMapper mapper = new ClassMapper();
    private final ClassService service = new ClassService(classes, members, mapper);
    private final UUID tutorId = UUID.randomUUID();
    private final UUID classId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(members.countByClassIdAndStatus(classId, ClassMember.MembershipStatus.ACTIVE))
                .thenReturn(3L);
    }

    @Test
    void createsClassOwnedByAuthenticatedTutor() {
        ClassCreationRequest request = new ClassCreationRequest();
        request.setClassCode("CS101");
        request.setTitle("Programming");
        request.setMaxCapacity(10);
        request.setEnrollmentPolicy(Class.EnrollmentPolicy.OPEN);
        when(classes.save(any(Class.class))).thenAnswer(call -> {
            Class saved = call.getArgument(0);
            ReflectionTestUtils.setField(saved, "classId", classId);
            return saved;
        });

        var response = service.createClass(request, tutorId);

        assertEquals(classId, response.classId());
        assertEquals(tutorId, response.tutorId());
        assertEquals("CS101", response.classCode());
        verify(classes).existsByClassCode("CS101");
        verify(classes).save(any(Class.class));
    }

    @Test
    void refusesDuplicateClassCode() {
        ClassCreationRequest request = new ClassCreationRequest();
        request.setClassCode("CS101");
        when(classes.existsByClassCode("CS101")).thenReturn(true);

        assertThrows(ClassCodeAlreadyExistsException.class,
                () -> service.createClass(request, tutorId));
        verify(classes, never()).save(any(Class.class));
    }

    @Test
    void tutorCanUpdateTheirOwnClass() {
        Class target = classEntity(tutorId);
        when(classes.findByIdForUpdate(classId)).thenReturn(Optional.of(target));
        when(classes.save(target)).thenReturn(target);
        ClassUpdateRequest request = updateRequest("Updated title", 8);

        Class result = service.updateClass(classId, tutorId, request);

        assertEquals("Updated title", result.getTitle());
        assertEquals(8, result.getMaxCapacity());
        verify(classes).save(target);
    }

    @Test
    void tutorCannotUpdateAnotherTutorsClass() {
        when(classes.findByIdForUpdate(classId)).thenReturn(Optional.of(classEntity(UUID.randomUUID())));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.updateClass(classId, tutorId, updateRequest("Changed", 8)));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(classes, never()).save(any(Class.class));
    }

    @Test
    void rejectsCapacityBelowCurrentActiveMembership() {
        when(classes.findByIdForUpdate(classId)).thenReturn(Optional.of(classEntity(tutorId)));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.updateClass(classId, tutorId, updateRequest(null, 2)));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("CAPACITY_BELOW_ACTIVE_MEMBERS", error.getReason());
        verify(classes, never()).save(any(Class.class));
    }

    @Test
    void returnsNotFoundWhenUpdatingMissingClass() {
        when(classes.findByIdForUpdate(classId)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.updateClass(classId, tutorId, updateRequest("Changed", 8)));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        assertEquals("CLASS_NOT_FOUND", error.getReason());
    }

    @Test
    void catalogNormalizesSearchAndMapsPageMetadataAndTutor() {
        ClassCatalogRow row = mock(ClassCatalogRow.class);
        UUID tutor = UUID.randomUUID();
        when(row.getClassId()).thenReturn(classId);
        when(row.getClassCode()).thenReturn("CS101");
        when(row.getTitle()).thenReturn("Programming");
        when(row.getTutorId()).thenReturn(tutor);
        when(row.getTutorName()).thenReturn("Tutor One");
        when(row.getEnrollmentPolicy()).thenReturn("OPEN");
        when(row.getStatus()).thenReturn("ACTIVE");
        when(row.getActiveMemberCount()).thenReturn(2L);
        when(row.getMaxCapacity()).thenReturn(10);
        var pageable = PageRequest.of(1, 12);
        when(classes.searchCatalog("programming", "OPEN", pageable))
                .thenReturn(new PageImpl<>(List.of(row), pageable, 13));

        var response = service.browseClasses("  programming  ", Class.EnrollmentPolicy.OPEN, pageable);

        assertEquals(2, response.page());
        assertEquals(12, response.size());
        assertEquals(13, response.totalItems());
        assertEquals(2, response.totalPages());
        assertEquals(classId, response.items().getFirst().classId());
        assertEquals("Tutor One", response.items().getFirst().tutor().fullName());
    }

    @Test
    void rejectsOverlongCatalogSearch() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.browseClasses("x".repeat(101), null, PageRequest.of(0, 12)));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(classes, never()).searchCatalog(any(), any(), any());
    }

    @Test
    void classDetailsIncludeTutorEmailAndLearningPrerequisiteInformation() {
        ClassDetailRow row = detailRow(tutorId, "ACTIVE");
        when(classes.findDetailsByClassId(classId)).thenReturn(Optional.of(row));

        var details = service.getClassDetails(classId, UUID.randomUUID(), false);

        assertEquals(classId, details.classId());
        assertEquals("Programming", details.title());
        assertEquals("Programming tutor", details.tutor().fullName());
        assertEquals("programming.tutor@example.test", details.tutor().email());
        assertEquals("Basic algebra", details.prerequisitesDescription());
        assertEquals(3L, details.activeMemberCount());
    }

    @Test
    void anotherTutorCanReadActiveClassOverview() {
        ClassDetailRow row = detailRow(tutorId, "ACTIVE");
        when(classes.findDetailsByClassId(classId)).thenReturn(Optional.of(row));

        var details = service.getClassDetails(classId, UUID.randomUUID(), false);

        assertEquals(classId, details.classId());
        assertEquals("ACTIVE", details.status());
        assertEquals("Programming tutor", details.tutor().fullName());
    }

    @Test
    void anotherTutorCannotReadArchivedClass() {
        ClassDetailRow row = detailRow(tutorId, "ARCHIVED");
        when(classes.findDetailsByClassId(classId)).thenReturn(Optional.of(row));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.getClassDetails(classId, UUID.randomUUID(), false));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        assertEquals("CLASS_NOT_FOUND", error.getReason());
    }

    @Test
    void owningTutorAndAdministratorStillSeeArchivedClass() {
        ClassDetailRow row = detailRow(tutorId, "ARCHIVED");
        when(classes.findDetailsByClassId(classId)).thenReturn(Optional.of(row));

        assertEquals("ARCHIVED", service.getClassDetails(classId, tutorId, false).status());
        assertEquals("ARCHIVED", service.getClassDetails(classId, UUID.randomUUID(), true).status());
    }

    @Test
    void studentCannotViewArchivedClassWithoutActiveMembership() {
        ClassDetailRow row = detailRow(tutorId, "ARCHIVED");
        when(classes.findDetailsByClassId(classId)).thenReturn(Optional.of(row));
        UUID studentId = UUID.randomUUID();
        when(members.existsByClassIdAndStudentIdAndStatus(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.getClassDetails(classId, studentId, false));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        assertEquals("CLASS_NOT_FOUND", error.getReason());
    }

    @Test
    void enrolledStudentCanViewArchivedClassTheyAttended() {
        ClassDetailRow row = detailRow(tutorId, "ARCHIVED");
        when(classes.findDetailsByClassId(classId)).thenReturn(Optional.of(row));
        UUID studentId = UUID.randomUUID();
        when(members.existsByClassIdAndStudentIdAndStatus(classId, studentId,
                ClassMember.MembershipStatus.ACTIVE)).thenReturn(true);

        assertEquals("ARCHIVED", service.getClassDetails(classId, studentId, false).status());
    }

    private ClassDetailRow detailRow(UUID ownerId, String status) {
        ClassDetailRow row = mock(ClassDetailRow.class);
        when(row.getClassId()).thenReturn(classId);
        when(row.getClassCode()).thenReturn("CS101");
        when(row.getTitle()).thenReturn("Programming");
        when(row.getDescription()).thenReturn("Intro course");
        when(row.getPrerequisitesDescription()).thenReturn("Basic algebra");
        when(row.getTutorId()).thenReturn(ownerId);
        when(row.getTutorName()).thenReturn("Programming tutor");
        when(row.getTutorEmail()).thenReturn("programming.tutor@example.test");
        when(row.getEnrollmentPolicy()).thenReturn("OPEN");
        when(row.getStatus()).thenReturn(status);
        when(row.getActiveMemberCount()).thenReturn(3L);
        when(row.getMaxCapacity()).thenReturn(10);
        return row;
    }
    private Class classEntity(UUID ownerId) {
        Class result = new Class(ownerId, "CS101", "Programming", null, null, 10,
                Class.EnrollmentPolicy.OPEN);
        ReflectionTestUtils.setField(result, "classId", classId);
        return result;
    }

    private ClassUpdateRequest updateRequest(String title, int capacity) {
        ClassUpdateRequest result = new ClassUpdateRequest();
        if (title != null) result.setTitle(title);
        result.setMaxCapacity(capacity);
        return result;
    }
}
