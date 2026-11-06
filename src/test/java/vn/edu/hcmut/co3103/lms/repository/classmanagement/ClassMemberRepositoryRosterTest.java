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
import vn.edu.hcmut.co3103.lms.entity.ClassMember;
import vn.edu.hcmut.co3103.lms.entity.ClassMember.MembershipStatus;
import vn.edu.hcmut.co3103.lms.repository.LmsUserAccessRepository;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:lms_roster;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;NON_KEYWORDS=CLASS"
}, showSql = false)
class ClassMemberRepositoryRosterTest {

    @Autowired
    private ClassMemberRepository memberRepository;

    @Autowired
    private ClassRepository classRepository;

    @Autowired
    private LmsUserAccessRepository userRepository;

    @Test
    void rosterSearchMatchesStudentNameOrEmailAndKeepsCountEqualToItems() {
        UUID classId = saveClass("CS-A");
        UUID ada = saveStudent("Ada Lovelace", "ada@example.test");
        UUID grace = saveStudent("Grace Hopper", "grace.hopper@navy.mil");
        enroll(classId, ada, MembershipStatus.ACTIVE, Instant.parse("2026-10-01T00:00:00Z"));
        enroll(classId, grace, MembershipStatus.ACTIVE, Instant.parse("2026-10-02T00:00:00Z"));

        var byName = memberRepository.searchRoster(classId, null, "LOVELACE", PageRequest.of(0, 20));
        assertThat(byName.getTotalElements()).isEqualTo(1);
        assertThat(byName.getContent()).extracting(ClassMember::getStudentId).containsExactly(ada);

        var byEmail = memberRepository.searchRoster(classId, null, "hopper@navy", PageRequest.of(0, 20));
        assertThat(byEmail.getTotalElements()).isEqualTo(1);
        assertThat(byEmail.getContent()).extracting(ClassMember::getStudentId).containsExactly(grace);

        var noMatch = memberRepository.searchRoster(classId, null, "no-such-student", PageRequest.of(0, 20));
        assertThat(noMatch.getTotalElements()).isZero();
        assertThat(noMatch.getContent()).isEmpty();
    }

    @Test
    void rosterSearchPaginatesAndSortsByEnrollmentDateWithConsistentTotal() {
        UUID classId = saveClass("CS-A");
        UUID an = saveStudent("Tran An", "an@example.test");
        UUID binh = saveStudent("Tran Binh", "binh@example.test");
        UUID cuong = saveStudent("Tran Cuong", "cuong@example.test");
        enroll(classId, an, MembershipStatus.ACTIVE, Instant.parse("2026-10-03T00:00:00Z"));
        enroll(classId, binh, MembershipStatus.ACTIVE, Instant.parse("2026-10-01T00:00:00Z"));
        enroll(classId, cuong, MembershipStatus.ACTIVE, Instant.parse("2026-10-02T00:00:00Z"));

        var sorted = Sort.by(Sort.Direction.ASC, "joined_at");
        var firstPage = memberRepository.searchRoster(classId, null, "tran",
                PageRequest.of(0, 2, sorted));
        var secondPage = memberRepository.searchRoster(classId, null, "tran",
                PageRequest.of(1, 2, sorted));

        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(firstPage.getContent()).extracting(ClassMember::getStudentId)
                .containsExactly(binh, cuong);
        assertThat(secondPage.getContent()).extracting(ClassMember::getStudentId)
                .containsExactly(an);
    }

    @Test
    void rosterSearchIsScopedToTheClassAndCombinedWithTheStatusFilter() {
        UUID firstClass = saveClass("CS-A");
        UUID secondClass = saveClass("CS-B");
        UUID shared = saveStudent("Marie Curie", "marie@example.test");
        enroll(firstClass, shared, MembershipStatus.ACTIVE, Instant.parse("2026-10-01T00:00:00Z"));
        enroll(secondClass, shared, MembershipStatus.PENDING, Instant.parse("2026-10-02T00:00:00Z"));

        var inFirstClass = memberRepository.searchRoster(firstClass, null, "curie", PageRequest.of(0, 20));
        assertThat(inFirstClass.getTotalElements()).isEqualTo(1);
        assertThat(inFirstClass.getContent().getFirst().getClassId()).isEqualTo(firstClass);

        var activeOnly = memberRepository.searchRoster(secondClass, MembershipStatus.ACTIVE.name(),
                "curie", PageRequest.of(0, 20));
        assertThat(activeOnly.getTotalElements()).isZero();

        var pendingOnly = memberRepository.searchRoster(secondClass, MembershipStatus.PENDING.name(),
                "curie", PageRequest.of(0, 20));
        assertThat(pendingOnly.getContent()).extracting(ClassMember::getStatus)
                .containsExactly(MembershipStatus.PENDING);
    }

    private UUID saveClass(String code) {
        return classRepository.save(new Class(UUID.randomUUID(), code, "Programming Basics", null, null,
                20, Class.EnrollmentPolicy.OPEN)).getClassId();
    }

    private UUID saveStudent(String name, String email) {
        UUID id = UUID.randomUUID();
        userRepository.saveAndFlush(new LmsUserAccess(id, name, email, LmsRole.STUDENT,
                AccessStatus.ACTIVE, null, null, null));
        return id;
    }

    private void enroll(UUID classId, UUID studentId, MembershipStatus status, Instant joinedAt) {
        memberRepository.saveAndFlush(new ClassMember(classId, studentId, status, null, joinedAt));
    }
}
