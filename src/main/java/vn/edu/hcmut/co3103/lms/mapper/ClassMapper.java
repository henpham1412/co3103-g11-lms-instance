package vn.edu.hcmut.co3103.lms.mapper;

import org.springframework.stereotype.Component;
import vn.edu.hcmut.co3103.lms.dto.request.ClassCreationRequest;
import vn.edu.hcmut.co3103.lms.dto.response.ClassCreationResponse;
import vn.edu.hcmut.co3103.lms.entity.Class;

import java.util.UUID;

@Component
public class ClassMapper {

    public Class toEntity(ClassCreationRequest request, UUID tutorId) {
        return new Class(
                tutorId,
                request.getClassCode(),
                request.getTitle(),
                request.getDescription(),
                request.getPrerequisitesDescription(),
                request.getMaxCapacity(),
                request.getEnrollmentPolicy()
        );
    }

    public ClassCreationResponse toCreationResponse(Class entity) {
        return new ClassCreationResponse(
                entity.getClassId(),
                entity.getTutorId(),
                entity.getClassCode(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getPrerequisitesDescription(),
                entity.getMaxCapacity(),
                entity.getEnrollmentPolicy(),
                entity.getStatus(),
                entity.getThumbnailUrl(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}