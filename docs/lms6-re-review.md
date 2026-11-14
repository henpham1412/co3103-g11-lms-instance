# LMS-6 PR #35 re-review

**MERGE BLOCKED: global V14 allocation and persistent-history coordination remain unresolved.**

These changes correct the four source-code findings on PR #35's existing branch,
starting from `1785b01685a334c1970a9bd35c3d7865f3f914c0`. They do not resolve the
separate migration collision. No production migration has been changed.

## Canonical creation and PATCH

`POST /api/v1/lessons/{lessonId}/activities` accepts `activity_type: ASSIGNMENT`
and an embedded `assignment` object with deadline, optional opening_time,
allow_late, allowed_file_types and max_file_size_mb. ChapterService keeps its
canonical Lesson/Class ownership and Class lock, creates the Activity and delegates
settings to AssignmentConfigurationService in that same transaction. Validation
or persistence failure rolls back both records. A bare Assignment placement is
rejected; non-Assignment placement behavior is unchanged. Quiz creation/configuration
is not reimplemented here.

`PATCH /api/v1/activities/{activityId}/assignment` updates only supplied permitted
fields. Omitted values survive; explicit null may clear opening_time, allowed file
types or max size. Null deadline/allow_late, unsupported fields/types, invalid
windows and nonpositive sizes are rejected. Existing future-deadline validation is
retained for creation and when a new deadline is supplied; an unrelated patch may
preserve an existing elapsed deadline (for example enabling late submissions).

The unapproved POST/PUT configuration routes are removed. Assignment placement,
title, instructions, ordering and visibility remain owned by Activity. The existing
GET configuration response and submission/grading DTO shapes remain unchanged.

## Access and source transactions

Assignment reads reuse ChapterService.getActivity for complete Activity → Lesson →
Chapter → Class visibility, ownership and active-membership checks. The Tutor flag
comes from the existing authenticated local authorities, not from a request field.
A hidden parent denies Student configuration, reference files and new submissions.
An owning Tutor can still read/manage hidden content in an active Class.

New Student work reuses a canonical content write guard: acquire the Class lock,
apply the existing read checks, then enforce the existing CLASS_ARCHIVED rule.
Tutor writes reuse the existing writable-Activity guard. The Class lock precedes
Assignment/Submission locks, serializing configuration, new work, grading and
canonical archive/content changes without reversing the established hierarchy.
Scalar scope reads occur before locking; managed assessment state is loaded after
that lock. PostgreSQL uniqueness and optimistic versioning remain enabled.

Archived Classes retain permitted historical reads for active members and the
owning Tutor. Inactive/former members do not gain new access; retained submissions
become readable again after authorized reactivation. New work is rejected in an
archived Class. Submission detail reads also verify member/Class equality and the
Student's actual ownership. No LMS-7 completion or LMS-9 calculation hooks are added.

Grading calls saveAndFlush before DTO construction. Real HTTP/PostgreSQL tests
compare response versions with committed rows, regrade with the returned version,
reject stale versions and race two writers with the same version. File replacement
keeps rollback cleanup and deletes the previous stored object only after commit.

## Content-based file validation

PDF/DOCX/PPTX reuse the existing MaterialContentValidator (PDFBox/POI). PNG/JPEG use
ImageIO; ordinary ZIP uses ZipFile; text is strictly decoded as UTF-8 and rejects
binary/control content. Malformed Office packages are not accepted as ordinary ZIP.
The client Content-Type is not used as content proof. The filename extension must
agree with the detected supported type, and allowed_file_types is normalized to
those explicit types, not arbitrary wildcards or an extension fallback.

Supported formats are PDF, DOCX, PPTX, TXT, PNG, JPG/JPEG and ZIP, with matching
extension or MIME configuration tokens. Existing byte fixtures cover PDF (including
encrypted PDF), DOCX and PPTX. Tests also generate actual PNG/JPEG/ZIP bytes and
cover renamed text/PDF, forged MIME, invalid binary, empty/header-only files, size
limits and path rejection. Failed uploads leave no stored orphan; failed replacement
preserves the old record/object. No new parser dependency is installed.

## Migration coordination: unresolved P1

Fresh remote inspection found:

| Owner/branch | Head | Allocated migrations |
|---|---|---|
| main | 2573f0d859c6c05050ffc9789eacdc70ef5d547f | V1–V13 |
| PR #31 / LMS-13 | 30a62a05906828261c105584582ad375b4abf552 | V14 index, V15 watermarks, V16 audit logs |
| PR #35 / LMS-6 | 1785b01685a334c1970a9bd35c3d7865f3f914c0 (review baseline) | V14 file assignments |
| LMS-9 | 17d548393f56e78deaf96dbd817f00c340a3df52 | V17 mastery/signals |
| PR #36 / LMS-10 | fbfda1995bd4b6d45ea616d9bd420f94b42e5845 | V18 discussions |
| PR #37 / LMS-11 | d24802a9aecde50f1c9e1fa712e92998fcb8e7d8 | Same V18, plus V19 reports |

The conflicting files are:

- PR #31 `V14__index_lms_user_access_default_sort.sql`: creates
  idx_lms_user_access_created_at_user_id on (created_at DESC, user_id ASC).
- PR #35 `V14__create_file_assignments.sql`: creates Assignment, reference attachment
  and Submission tables, constraints and indexes. Its original bytes and pinned
  checksum 1890549139 are unchanged.

PR #37 contains PR #36's HEAD and identical V18 bytes; that is inherited content,
not two different V18 implementations. LMS-9 still requires its documented D-14
integration gate to be checked before shared V17 rollout.

No current shared/persistent Flyway history or deployment connection was available.
The existing development PostgreSQL container was stopped and was not started or
changed. All executed database verification uses a new PostgreSQL 18.6 container
with tmpfs storage and newly created disposable databases. Their migration histories
do not prove which versions have been applied in persistent environments.

A safe global sequence cannot be assigned yet. The integration owner must inventory
applied versions/checksums, decide the canonical V14 lineage with the LMS-13/LMS-6
owners, then coordinate any verified-unapplied artifact allocation while preserving
the retained V14–V16 → V17 → V18 → V19 order. No replacement number is assumed free.
If an affected migration has been applied persistently, preserve its version/bytes
and agree on forward corrective migrations and a tested convergence plan. Do not
renumber, reset or repair that history.

PR #35 cannot retain its current V14 alongside PR #31's different V14 merely by
choosing a merge order. It could retain V14 only after an owner-approved global
plan selects that lineage and safely coordinates all other affected allocations;
there is currently insufficient evidence/authorization to do that. If PR #35's V14
is confirmed never applied persistently, coordinated changes would include its
filename/allocation, MigrationPolicyTest pin and upgrade fixtures. If its V14 is
already applied, other never-applied allocations would need their owners' action.
No related PR/branch is changed by this work.

AssignmentMigrationPostgresTest verifies standalone V13 → V14 with earlier checksums
unchanged and explicitly asserts Flyway's duplicate-version rejection when combined
with PR #31's exact V14 test fixture. That fixture is not a production migration.
Passing this expected-failure test does not make the combined rollout safe.

## Verification commands

JDK 25, PostgreSQL 18.6; DB_USERNAME=postgres and blank DB_PASSWORD only on the
new loopback/tmpfs test instance. Each group uses its own disposable database:

```sh
DB_URL=jdbc:postgresql://localhost:55432/lms6_fix_clean_focused DB_USERNAME=postgres DB_PASSWORD='' /workspace/.setup/mvn -o -B --no-transfer-progress -Dtest=AssignmentServiceTest,AssignmentFileStorageServiceTest,AssignmentPostgresHttpTest,AssignmentMigrationPostgresTest,MigrationPolicyTest,ChapterServiceTest clean test
DB_URL=jdbc:postgresql://localhost:55432/lms6_fix_clean_focused DB_USERNAME=postgres DB_PASSWORD='' /workspace/.setup/mvn -o -B --no-transfer-progress -Dtest=AssignmentServiceTest,AssignmentFileStorageServiceTest,AssignmentPostgresHttpTest,AssignmentMigrationPostgresTest,MigrationPolicyTest,ChapterServiceTest test
DB_URL=jdbc:postgresql://localhost:55432/lms6_fix_regression DB_USERNAME=postgres DB_PASSWORD='' /workspace/.setup/mvn -o -B --no-transfer-progress -Dtest=ClassServiceTest,ClassMembershipServiceTest,ClassControllerApiTest,ClassPatchContractHttpTest,ChapterServiceTest,LearningContentHttpTest,QuizContractPostgresTest,LearningProgressPostgresHttpTest,Lms7ProgressEndpointsTest,TutorAndAssessmentHistoryServiceTest,Lms8AdaptivePostgresHttpTest test
DB_URL=jdbc:postgresql://localhost:55432/lms6_fix_full DB_USERNAME=postgres DB_PASSWORD='' /workspace/.setup/mvn -o -B --no-transfer-progress clean verify
git diff --check
```

The first non-clean HTTP run included stale compiled LMS-9 resources from the
previous checkout and is excluded from final baseline evidence. Clean verification
uses only the PR #35 source/resource tree. Existing assertions were preserved.
LMS-9-only suites are not on this branch and were not claimed as executed here.
No authoritative DOC-01–DOC-07 changes, Assignment mastery integration, GitHub
metadata changes or merge authorization are included.

## Executed results

- Initial clean focused group: 74 passed. After adding strict-list validation,
  deadline/late-policy and local-access cases, the final focused group passed 78:
  AssignmentPostgresHttpTest 28, AssignmentFileStorageServiceTest 12,
  AssignmentServiceTest 2, AssignmentMigrationPostgresTest 2, MigrationPolicyTest 3,
  ChapterServiceTest 31. Zero failures/errors/skips.
- LMS-3/4/5/7/8 regression group: 239 passed, zero failures/errors/skips.
- Full clean verify: 1,311 passed, zero failures/errors/skips; BUILD SUCCESS, 87 seconds.
- Working-tree and staged diff checks passed. No final verification failure remains.
- Expected rejection tests produce server-error/SQL logs for the scoped persistence
  rollback fixture and the deliberately conflicting migration set; these are asserted
  outcomes, not verification failures.

The temporary PostgreSQL instance was provisioned without a persistent volume:

```sh
docker run -d --name lms6-fix-postgres --tmpfs /var/lib/postgresql -p 127.0.0.1:55432:5432 -e POSTGRES_HOST_AUTH_METHOD=trust postgres:18.6 -c max_connections=300 -c log_statement=none -c log_min_error_statement=panic -c log_parameter_max_length=0 -c log_parameter_max_length_on_error=0
# After pg_isready succeeds, create the three dedicated databases used above.
docker exec lms6-fix-postgres createdb -U postgres lms6_fix_clean_focused
docker exec lms6-fix-postgres createdb -U postgres lms6_fix_regression
docker exec lms6-fix-postgres createdb -U postgres lms6_fix_full
```

## Exact competing V14 SQL at the inspected heads

PR #31 at 30a62a05906828261c105584582ad375b4abf552:

```sql
CREATE INDEX idx_lms_user_access_created_at_user_id
    ON lms_user_access (created_at DESC, user_id ASC);
```

PR #35 V14 (unchanged from 1785b01685a334c1970a9bd35c3d7865f3f914c0):

```sql
CREATE TABLE assignment (
    assignment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    activity_id UUID NOT NULL UNIQUE REFERENCES activity(activity_id),
    opening_time TIMESTAMPTZ,
    deadline TIMESTAMPTZ NOT NULL,
    allow_late BOOLEAN NOT NULL DEFAULT FALSE,
    allowed_file_types VARCHAR(1000),
    max_file_size_mb INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_assignment_window CHECK (opening_time IS NULL OR opening_time < deadline),
    CONSTRAINT chk_assignment_max_file_size CHECK (max_file_size_mb IS NULL OR max_file_size_mb > 0)
);

CREATE TABLE assignment_attachment (
    attachment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id UUID NOT NULL REFERENCES assignment(assignment_id) ON DELETE CASCADE,
    file_name TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0)
);

CREATE INDEX idx_assignment_attachment_assignment ON assignment_attachment(assignment_id);

CREATE TABLE submission (
    submission_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id UUID NOT NULL REFERENCES assignment(assignment_id) ON DELETE CASCADE,
    class_member_id UUID NOT NULL REFERENCES class_member(class_member_id),
    file_name TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_late BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    score NUMERIC(5, 2),
    feedback TEXT,
    graded_by UUID REFERENCES lms_user_access(user_id),
    graded_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_submission_assignment_member UNIQUE (assignment_id, class_member_id),
    CONSTRAINT chk_submission_status CHECK (status IN ('SUBMITTED', 'GRADED')),
    CONSTRAINT chk_submission_score CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    CONSTRAINT chk_submission_grade_fields CHECK (
        (status = 'SUBMITTED' AND score IS NULL AND graded_by IS NULL AND graded_at IS NULL)
        OR (status = 'GRADED' AND score IS NOT NULL AND graded_by IS NOT NULL AND graded_at IS NOT NULL)
    )
);

CREATE INDEX idx_submission_assignment_status ON submission(assignment_id, status);
CREATE INDEX idx_submission_member ON submission(class_member_id);
```

## Changed files

- `README.md`
- `docs/lms6-re-review.md`
- `src/main/java/vn/edu/hcmut/co3103/lms/controller/assessment/AssignmentController.java`
- `src/main/java/vn/edu/hcmut/co3103/lms/dto/request/ActivityRequest.java`
- `src/main/java/vn/edu/hcmut/co3103/lms/repository/assessment/AssignmentRepository.java`
- `src/main/java/vn/edu/hcmut/co3103/lms/repository/assessment/SubmissionRepository.java`
- `src/main/java/vn/edu/hcmut/co3103/lms/service/assessment/AssignmentConfigurationService.java`
- `src/main/java/vn/edu/hcmut/co3103/lms/service/assessment/AssignmentFileStorageService.java`
- `src/main/java/vn/edu/hcmut/co3103/lms/service/assessment/AssignmentFileTypes.java`
- `src/main/java/vn/edu/hcmut/co3103/lms/service/assessment/AssignmentService.java`
- `src/main/java/vn/edu/hcmut/co3103/lms/service/learningcontent/ChapterService.java`
- `src/test/java/vn/edu/hcmut/co3103/lms/bootstrap/AssignmentMigrationPostgresTest.java`
- `src/test/java/vn/edu/hcmut/co3103/lms/controller/assessment/AssignmentPostgresHttpTest.java`
- `src/test/java/vn/edu/hcmut/co3103/lms/service/assessment/AssignmentFileStorageServiceTest.java`
- `src/test/java/vn/edu/hcmut/co3103/lms/service/assessment/AssignmentServiceTest.java`
- `src/test/java/vn/edu/hcmut/co3103/lms/service/learningcontent/ChapterServiceTest.java`
- `src/test/resources/assignment/lms13-30a62a0/V14__index_lms_user_access_default_sort.sql`
