package vn.edu.hcmut.co3103.lms.service.classmanagement;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;

import java.util.UUID;

@Service
public class ClassThumbnailService {
    private final ClassRepository classRepository;
    private final ClassThumbnailStorageService storageService;

    public ClassThumbnailService(ClassRepository classRepository, ClassThumbnailStorageService storageService) {
        this.classRepository = classRepository;
        this.storageService = storageService;
    }

    @Transactional
    public String upload(UUID classId, UUID tutorId, MultipartFile file) {
        Class target = classRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CLASS_NOT_FOUND"));
        if (!target.getTutorId().equals(tutorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        ClassThumbnailStorageService.StoredThumbnail stored = storageService.store(file);
        target.setThumbnailUrl(stored.url());
        try {
            classRepository.saveAndFlush(target);
        } catch (RuntimeException exception) {
            storageService.delete(stored.fileName());
            throw exception;
        }
        return stored.url();
    }
}
