package vn.edu.hcmut.co3103.lms.service.classmanagement;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.Class;
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

class ClassThumbnailServiceTest {
    private final ClassRepository classRepository = mock(ClassRepository.class);
    private final ClassThumbnailStorageService storageService = mock(ClassThumbnailStorageService.class);
    private final ClassThumbnailService service = new ClassThumbnailService(classRepository, storageService);
    private final UUID classId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final MockMultipartFile file = new MockMultipartFile(
            "file", "cover.png", "image/png", new byte[]{1, 2, 3});

    @Test
    void savesThumbnailUrlForOwningTutor() {
        Class target = ownedClass();
        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.of(target));
        when(storageService.store(file)).thenReturn(new ClassThumbnailStorageService.StoredThumbnail(
                "123e4567-e89b-12d3-a456-426614174000.png", "image/png",
                "http://localhost:8081/class-thumbnails/123e4567-e89b-12d3-a456-426614174000.png"));

        String url = service.upload(classId, ownerId, file);

        assertEquals(url, target.getThumbnailUrl());
        verify(classRepository).saveAndFlush(target);
    }

    @Test
    void rejectsNonOwnerBeforeWritingFile() {
        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.of(ownedClass()));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.upload(classId, UUID.randomUUID(), file));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(storageService, never()).store(any());
        verify(classRepository, never()).saveAndFlush(any());
    }

    @Test
    void reportsMissingClassWithoutWritingFile() {
        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.upload(classId, ownerId, file));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(storageService, never()).store(any());
    }

    @Test
    void deletesStoredFileWhenDatabaseWriteFails() {
        Class target = ownedClass();
        String fileName = "123e4567-e89b-12d3-a456-426614174000.png";
        when(classRepository.findByIdForUpdate(classId)).thenReturn(Optional.of(target));
        when(storageService.store(file)).thenReturn(new ClassThumbnailStorageService.StoredThumbnail(
                fileName, "image/png", "http://localhost:8081/class-thumbnails/" + fileName));
        when(classRepository.saveAndFlush(target)).thenThrow(new IllegalStateException("database failure"));

        assertThrows(IllegalStateException.class, () -> service.upload(classId, ownerId, file));

        verify(storageService).delete(fileName);
    }

    private Class ownedClass() {
        Class target = new Class(ownerId, "CS101", "Programming", null, null, 20,
                Class.EnrollmentPolicy.OPEN);
        ReflectionTestUtils.setField(target, "classId", classId);
        return target;
    }
}
