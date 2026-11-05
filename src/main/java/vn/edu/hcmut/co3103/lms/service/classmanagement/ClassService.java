package vn.edu.hcmut.co3103.lms.service.classmanagement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.hcmut.co3103.lms.dto.request.ClassCreationRequest;
import vn.edu.hcmut.co3103.lms.dto.request.ClassUpdateRequest;
import vn.edu.hcmut.co3103.lms.dto.response.ClassCatalogResponse;
import vn.edu.hcmut.co3103.lms.dto.response.ClassCreationResponse;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.exception.ClassCodeAlreadyExistsException;
import vn.edu.hcmut.co3103.lms.mapper.ClassMapper;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassCatalogRow;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassMemberRepository;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;

import java.util.List;
import java.util.UUID;

@Service
public class ClassService {

    private final ClassRepository classRepository;
    private final ClassMemberRepository classMemberRepository;
    private final ClassMapper classMapper;

    public ClassService(
            ClassRepository classRepository,
            ClassMemberRepository classMemberRepository,
            ClassMapper classMapper
    ) {
        this.classRepository = classRepository;
        this.classMemberRepository = classMemberRepository;
        this.classMapper = classMapper;
    }

    @Transactional
    public ClassCreationResponse createClass(
            ClassCreationRequest request,
            UUID tutorId
    ) {
        if (classRepository.existsByClassCode(request.getClassCode())) {
            throw new ClassCodeAlreadyExistsException();
        }

        Class newClass = classMapper.toEntity(request, tutorId);
        Class savedClass = classRepository.save(newClass);
        return classMapper.toCreationResponse(savedClass);
    }

    @Transactional(readOnly = true)
    public ClassCatalogResponse browseClasses(
            String query,
            Class.EnrollmentPolicy enrollmentPolicy,
            Pageable pageable
    ) {
        if (query != null && query.length() > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_QUERY_PARAMETER"
            );
        }

        String normalizedQuery = query == null || query.isBlank()
                ? null
                : query.trim();
        String policy = enrollmentPolicy == null ? null : enrollmentPolicy.name();
        Page<ClassCatalogRow> result = classRepository.searchCatalog(
                normalizedQuery,
                policy,
                pageable
        );

        List<ClassCatalogResponse.Item> items = result.getContent().stream()
                .map(row -> new ClassCatalogResponse.Item(
                        row.getClassId(),
                        row.getClassCode(),
                        row.getTitle(),
                        row.getDescription(),
                        row.getPrerequisitesDescription(),
                        row.getThumbnailUrl(),
                        row.getEnrollmentPolicy(),
                        row.getStatus(),
                        new ClassCatalogResponse.Tutor(row.getTutorId(), row.getTutorName()),
                        row.getActiveMemberCount(),
                        row.getMaxCapacity()
                ))
                .toList();

        return new ClassCatalogResponse(
                items,
                result.getNumber() + 1,
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public ClassCatalogResponse.Item getClassDetails(
            UUID classId,
            UUID viewerId,
            boolean tutor,
            boolean administrator
    ) {
        ClassCatalogRow row = classRepository.findDetailsByClassId(classId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "CLASS_NOT_FOUND"
                ));

        if (tutor && !row.getTutorId().equals(viewerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }
        if (!tutor && !administrator
                && "ARCHIVED".equals(row.getStatus())
                && !classMemberRepository.existsByClassIdAndStudentIdAndStatus(
                        classId,
                        viewerId,
                        ClassMember.MembershipStatus.ACTIVE
                )) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CLASS_NOT_FOUND");
        }

        return new ClassCatalogResponse.Item(
                row.getClassId(),
                row.getClassCode(),
                row.getTitle(),
                row.getDescription(),
                row.getPrerequisitesDescription(),
                row.getThumbnailUrl(),
                row.getEnrollmentPolicy(),
                row.getStatus(),
                new ClassCatalogResponse.Tutor(row.getTutorId(), row.getTutorName()),
                row.getActiveMemberCount(),
                row.getMaxCapacity()
        );
    }
    @Transactional(readOnly = true)
    public Page<ClassCreationResponse> listTutorClasses(UUID tutorId, Pageable pageable) {
        return classRepository.findByTutorId(tutorId, pageable)
                .map(classMapper::toCreationResponse);
    }

    @Transactional
    public Class updateClass(
            UUID classId,
            UUID tutorId,
            ClassUpdateRequest request
    ) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
        Class targetClass = classRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "CLASS_NOT_FOUND"
                ));

        if (!targetClass.getTutorId().equals(tutorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        validateUpdateRequest(request);
        validateCapacityChange(targetClass, request);

        if (request.isTitleProvided()) {
            targetClass.setTitle(request.getTitle().trim());
        }
        if (request.isDescriptionProvided()) {
            targetClass.setDescription(request.getDescription());
        }
        if (request.isPrerequisitesDescriptionProvided()) {
            targetClass.setPrerequisitesDescription(request.getPrerequisitesDescription());
        }
        if (request.isMaxCapacityProvided()) {
            targetClass.setMaxCapacity(request.getMaxCapacity());
        }
        if (request.isEnrollmentPolicyProvided()) {
            targetClass.setEnrollmentPolicy(request.getEnrollmentPolicy());
        }
        if (request.isStatusProvided()) {
            targetClass.setStatus(request.getStatus());
        }

        return classRepository.save(targetClass);
    }

    private void validateUpdateRequest(ClassUpdateRequest request) {
        if (!request.hasAnyField()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
        if (request.isTitleProvided()
                && (request.getTitle() == null
                || request.getTitle().isBlank()
                || request.getTitle().length() > 150)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
        if (request.isDescriptionProvided()
                && request.getDescription() != null
                && request.getDescription().length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
        if (request.isMaxCapacityProvided()
                && request.getMaxCapacity() != null
                && request.getMaxCapacity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
        if (request.isEnrollmentPolicyProvided()
                && request.getEnrollmentPolicy() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
        if (request.isStatusProvided() && request.getStatus() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
        }
    }

    private void validateCapacityChange(Class targetClass, ClassUpdateRequest request) {
        if (!request.isMaxCapacityProvided() || request.getMaxCapacity() == null) {
            return;
        }

        long activeCount = classMemberRepository.countByClassIdAndStatus(
                targetClass.getClassId(),
                ClassMember.MembershipStatus.ACTIVE
        );
        if (request.getMaxCapacity() < activeCount) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "CAPACITY_BELOW_ACTIVE_MEMBERS"
            );
        }
    }
}
