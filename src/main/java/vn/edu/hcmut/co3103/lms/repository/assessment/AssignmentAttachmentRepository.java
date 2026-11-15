package vn.edu.hcmut.co3103.lms.repository.assessment;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.AssignmentAttachment;

import java.util.List;
import java.util.UUID;

public interface AssignmentAttachmentRepository extends JpaRepository<AssignmentAttachment, UUID> {
    List<AssignmentAttachment> findByAssignmentIdOrderByFileNameAsc(UUID assignmentId);
}
