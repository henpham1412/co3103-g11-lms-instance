package vn.edu.hcmut.co3103.lms.repository.classmanagement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import vn.edu.hcmut.co3103.lms.domain.AccessStatus;
import vn.edu.hcmut.co3103.lms.domain.LmsRole;
import vn.edu.hcmut.co3103.lms.domain.LmsUserAccess;
import vn.edu.hcmut.co3103.lms.entity.Class;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:lms_catalog;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=CLASS"
}, showSql = false)
class ClassRepositoryCatalogTest {
    @Autowired
    private ClassRepository classRepository;

    @Autowired
    private LmsUserAccessRepository userRepository;

    @Test
    void catalogSearchesTutorAndClassFieldsFiltersPolicyAndUsesRequestedPageSort() {
        UUID tutorId = saveUser("Ada Lovelace", LmsRole.TUTOR);
        saveUser("Grace Hopper", LmsRole.TUTOR);
        classRepository.save(new Class(tutorId, "CS-A", "Programming Basics", null, null, 20,
                Class.EnrollmentPolicy.OPEN));
        classRepository.save(new Class(tutorId, "CS-B", "Advanced Programming", null, null, 20,
                Class.EnrollmentPolicy.APPROVAL_REQUIRED));
        UUID otherTutor = userRepository.findAll().stream()
                .filter(user -> user.getFullName().equals("Grace Hopper"))
                .findFirst().orElseThrow().getUserId();
        classRepository.save(new Class(otherTutor, "DB-A", "Database Basics", null, null, 20,
                Class.EnrollmentPolicy.OPEN));
        classRepository.flush();

        var result = classRepository.searchCatalog("ada", "OPEN",
                PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "created_at")));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().getClassCode()).isEqualTo("CS-A");
        assertThat(result.getContent().getFirst().getTutorName()).isEqualTo("Ada Lovelace");
    }

    private UUID saveUser(String name, LmsRole role) {
        UUID id = UUID.randomUUID();
        userRepository.saveAndFlush(new LmsUserAccess(id, name, id + "@example.test", role,
                AccessStatus.ACTIVE, null, null, null));
        return id;
    }
}
