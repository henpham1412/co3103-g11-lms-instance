package vn.edu.hcmut.co3103.lms.repository.learningcontent;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.edu.hcmut.co3103.lms.entity.LastVisit;

import java.util.UUID;

public interface LastVisitRepository extends JpaRepository<LastVisit, UUID> {
}
