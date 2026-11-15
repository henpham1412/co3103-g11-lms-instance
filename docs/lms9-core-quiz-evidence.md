# LMS-9 Phase 2B: core Quiz evidence reader

Status: D-08 historical Quiz evidence working contract implemented for review.
The project coordinator authorized this implementation on 2026-11-13 using the
handoff's proposed default. This does not establish formal whole-group approval.
DOC-01 through DOC-07 remain the requirements source of truth and are unchanged.

`TopicQuizEvidenceReader.readLatestGradedCandidates(classId, classMemberId, skillTopicId)`
returns at most three internal evidence values containing the attempt, member,
topic, original submission timestamp and calculated topic score. Phase 3 now
registers it as an internal bean used by transactional evidence synchronization;
it has no API or recommendation integration. It is not an authorization boundary:
callers must establish permission separately and supply trusted scope IDs. See
[Phase 3 persistence](lms9-mastery-persistence.md) for the source integration.

## Core query contract

- Require `SUBMITTED`, non-null score, grading timestamp and submission timestamp.
- Resolve Quiz → Activity → Lesson → Chapter and require the Chapter, ClassMember
  and SkillTopic to belong to the requested class. Activity must have type QUIZ.
- Join all explicitly mapped Questions, then LEFT JOIN answers on both attempt ID
  and question ID. Missing answers contribute zero without removing possible points.
- Sum canonical stored earned points, including deductions, and Question possible
  points. Do not join options, regrade answers or use `SUM(DISTINCT points)`.
- Group once per attempt/member/topic, exclude non-positive denominators, then
  order across Quizzes by `submitted_at DESC, attempt_id DESC` and limit to three.
- Use the existing pure MasteryCalculator for clamping and two-decimal topic scores.
  Component aggregation remains the calculator's separate responsibility.
- A single PostgreSQL statement provides one snapshot for points and mappings.

## D-08 historical evidence eligibility: coordinator-authorized working policy

An attempt contributes only when it is SUBMITTED and has a non-null canonical
score, graded_at and original submitted_at. These are the actual upstream
submission/grading-completion indicators; answer rows can exist with zero points
before grading and do not independently establish eligibility. The referenced
Quiz/content, ClassMember, SkillTopic and Question mapping records must exist and
pass the query's class-scope checks. A topic denominator must be positive.

Previously valid graded evidence remains eligible after:

- The Quiz Activity, Lesson or Chapter becomes HIDDEN.
- The Class becomes ARCHIVED.
- Current membership changes, including INACTIVE, PENDING or REJECTED.
- The learner's local access becomes SUSPENDED.

These are historical-evidence rules, not current access grants. The reader has no
membership-status, publication-status, archive-status or local-access filter.
Existing protected learning operations continue enforcing their own authorization
and lifecycle restrictions. Future recommendation selection must independently
exclude unavailable Activities. The reader remains internal. The D-08 task added
no API or persistence; subsequent Phase 3 integration preserves this policy.

All grading eligibility, scope, mapping and denominator checks run BEFORE
`ORDER BY submitted_at DESC, attempt_id DESC LIMIT 3`. Thus an ungraded newest
attempt cannot displace an older eligible third result. Selection is per requested
ClassMember/SkillTopic across Quizzes. Regrading changes canonical points and
graded_at, not the original submission ordering. Do not filter an already limited
window when introducing any later source-validity rule.

## Canonical lifecycle limitations

- QuizAttempt supports only IN_PROGRESS and SUBMITTED; no cancellation,
  invalidation, deleted_at or soft-delete state exists in the inspected canonical
  model. No additional predicates or invented states were added.
- Content publication supports PUBLISHED/HIDDEN, not a separate UNPUBLISHED state.
  "Unpublished" historical content therefore uses the existing HIDDEN state.
- Physical deletion is observed through current rows, not a retained snapshot.
  A deleted attempt cannot contribute; a PostgreSQL test deletes its dependent
  answers before the attempt, respecting canonical foreign keys, and verifies
  promotion of the fourth result. This is storage-level robustness, not a new
  application deletion workflow. Parent records with retained dependent data are
  restricted by existing foreign keys and upstream retention rules.
- Missing answer rows remain unanswered Questions with zero earned points. Their
  absence must not be treated as an invented invalidation signal.
- There is no merged local Administration API in this baseline. Local suspension
  fixtures use the canonical lms_user_access.access_status column; actual existing
  authentication/authorization still rejects the suspended Student's HTTP request.
- An explicit future cancellation/invalidation mechanism requires its own approved
  contract. It cannot be tested as an existing lifecycle state here.

## Decisions still pending

- **D-02:** UUID descending is a deterministic provisional secondary key, awaiting
  confirmation. It is not a newly approved business ordering rule.
- **D-07:** topic-score HALF_UP remains the explicitly provisional Phase 1 assumption.
- D-08 is now the coordinator-authorized working contract above; formal group
  confirmation is not recorded. D-09 through D-13 and D-14 coordination remain
  outside this task. No migration version or next-phase implementation is approved
  by this document update.

Mappings remain canonical current mappings, not historical snapshots. Phase 3
synchronizes persisted signals and mastery after the final source transaction state.

## Verification and performance

`TopicQuizEvidenceReaderPostgresTest` runs against real PostgreSQL and canonical
Flyway migrations, without a Spring application context or repository mocks.
Each test uses a transaction that rolls back its fixture. Supply the normal
`DB_URL`, `DB_USERNAME` and `DB_PASSWORD` settings and use a disposable test database.

Coverage includes unanswered/all-unanswered attempts; deductions and unequal or
identical point values; multiple topics and Quizzes; no mapping/zero denominator;
all four grading gates before limiting; latest-three and UUID ties crossing the
signed Java UUID boundary; changed grades without changed submission order;
mapping additions/removals and fourth-result promotion; and member/class/hierarchy
isolation even for deliberately inconsistent cross-table fixtures.

D-08 regressions additionally verify all four eligibility gates with a newer
ineligible attempt and four older eligible results, retention across each content/
class/membership/local-access state above, and physical-attempt deletion with
fourth-result promotion. Lms8AdaptivePostgresHttpTest exercises real submission and
grading, then actual content-hide, class-archive and member-removal APIs: historical
evidence remains unchanged while the Student's current result request is denied.
The canonical submission/grading/regrading timestamp regression remains in place.

The EXPLAIN test executes the actual aggregate statement with ANALYZE and BUFFERS
on 240 attempts with two mapped Questions. On PostgreSQL 18.6, the focused run on
2026-11-12 measured 4.026 ms execution and 4.736 ms planning. This is a small-fixture
measurement, not production performance evidence. No additional index or migration
is justified by that measurement. If larger representative workloads require an
index, inspect the query plan and follow D-14 allocation checks before proposing
any migration version. No version is allocated here.
