# LMS-9 Phase 6: Assignment evidence

## Baseline and scope

Integrated backend main / LMS-6 PR #35 merge `69d44ae58c5568e5850e450efc1c0223f83f53b3`
into `feat/lms-9-quiz-evidence`, preserving Phase 5 `17d548393f56e78deaf96dbd817f00c340a3df52`
and its ancestors. Assignment evidence extends the existing mastery pipeline. It does not add
Assignment recommendation candidates, frontend changes, public endpoints, entities or migrations.
DOC-01–DOC-07 and the accepted Quiz calculator/reader remain unchanged.

## Canonical source and reader

`Submission.score` is already a BigDecimal percentage (database NUMERIC(5,2), 0–100).
There is no maximum-score field or points denominator. Each explicit ActivitySkillTopic receives
the full percentage once; scores are not divided between topics. `AssignmentAttachment` files
are not evidence and are not joined.

`TopicAssignmentEvidenceReader` follows Submission → Assignment → Activity → Lesson → Chapter,
Submission → ClassMember, and explicit ActivitySkillTopic → SkillTopic. The requested ClassMember,
Chapter and SkillTopic must all have the requested Class ID; Activity must have type ASSIGNMENT.
An EXISTS mapping predicate avoids multiplying sources. Eligible rows require status GRADED,
non-null score, graded_at and submitted_at, and score BETWEEN 0 AND 100.

Historical evidence deliberately has no current visibility, archive, membership or local-access
predicate. Current action/API authorization remains in the canonical services. Retained history
never authorizes a former member, foreign Tutor or suspended user to use a protected API.

Both reader methods use PostgreSQL `ORDER BY submitted_at DESC, submission_id DESC`.
`readLatestGradedCandidates` appends LIMIT 3 after all eligibility/mapping predicates.
`readAllGradedCandidates` supplies all signals in the identical order; synchronization selects
the first three from that ordered snapshot for calculation. There is no Java UUID re-sort.

Each topic result uses `score.setScale(2, RoundingMode.HALF_UP)`. Canonical persisted values already
have scale two. Invalid percentages/incomplete GRADED rows are also prevented by LMS-6 validation
and schema constraints; no denominator or fabricated zero is introduced.

## Components and persistence

The existing MasteryCalculator is unchanged: exact BigDecimal sums, DECIMAL128 division, no
intermediate two-decimal rounding, and classification of the calculated combined value at 50/80.
Each component averages up to its own latest three eligible sources, across different assessments.

- Quiz 90,90,90 and Assignment 30 produce (90+30)/2 = 60, DEVELOPING; never pooled average 75.
- One available component is used alone.
- An Assignment score of zero is real evidence, producing WEAK/0 when used alone.
- Neither component means no current LearnerMastery row. Lesson completion stays contextual.

Assignment signals use ASSIGNMENT_RESULT / SUBMISSION / submissionId. `recorded_at` is submitted_at,
not grading time. The existing uniqueness constraint includes member, topic, signal type, source
type and UUID source ID. Regrading updates the existing signal; remapping/deletion reconciliation
removes obsolete signals. Quiz signals are reconciled separately, retaining their original identities.

## Transactions, regrading and mapping

AssignmentService.grade keeps its existing authorization, Class lock, Submission lock and optimistic
version behavior. After saveAndFlush, it calls MasteryRecalculationService.synchronizeSubmission in
the same transaction (MANDATORY). The reader sees the stored, database-rounded score. Signals and
combined mastery commit with grading, or all roll back. No asynchronous/after-commit synchronization
or SecurityContext-derived learner identity is used.

Grading/regrading modifies the same Submission and graded_at; submitted_at is unchanged. Before
grading, a permitted file replacement updates submitted_at and clears grading fields. Such uploads
and replacements do not create evidence. Graded replacement remains prohibited.

replaceActivityTopics captures previous/current Assignment topics, flushes mappings and synchronizes
their union under the canonical Class lock. Existing source-signal topics are also discovered for
retry cleanup; source learners are resolved through canonical submissions. The Quiz shared mapping
helper and QuestionSkillTopic calculation behavior are unchanged.

## Required historical reconciliation / operator integration

The transactional internal entry point is:

```java
applicationContext.getBean(MasteryRecalculationService.class)
        .reconcileAssignments(classId);
```

This must be invoked through the Spring-managed proxy from a trusted, operator-controlled application
context. There is no public REST endpoint, automatic startup rebuild or new CLI command. The repository
has no established operator invocation harness; deployment integration must supply an approved,
authenticated operator runner that boots the same application/profile and invokes this method for an
explicit Class UUID. Do not expose the bean through an ordinary learner/Tutor endpoint or bypass the
proxy with direct construction/self-invocation. No direct SQL backfill is required.

Rollout gate: after deploying Phase 6, an authorized operator must invoke this method for each relevant
Class before declaring existing Assignment history reconciled. This task exercises it only against
disposable PostgreSQL; it does not invoke it against shared/development/production data.

One invocation locks the Class row and executes one atomic transaction. It discovers unique member/topic
pairs from current Submission mappings, existing Assignment signals (including orphaned source IDs), and
current mastery rows. Membership/archive/visibility does not exclude historical pairs. Each pair rebuilds
both components through the same readers and persistence logic, preserving Quiz-only evidence. Other
Classes are untouched. Pairs are processed deterministically. Existing graded history contributes without
requiring Tutors to regrade it.

If any pair fails, all derived changes for that Class roll back. Retry the same Class UUID after resolving
the cause. If an external operator processes multiple Classes and stops partway, completed Classes remain
committed; retrying them is safe. Source identities, values and resulting mastery converge; existing source
IDs are retained. Existing mastery update timestamps may refresh under the established update policy.

Verification should confirm every eligible source/topic has one correct signal (value and original event
time), orphan/obsolete Assignment signals are absent, and latest-three component calculation matches current
mastery. Use authorized internal inspection or tests; the public Tutor API intentionally excludes inactive
members. A no-op/retry must not increase source counts or change source identities/calculated mastery.

There is no canonical deletion API. If a future trusted workflow deletes a source or cascades an Assignment,
invoke reconciliation in its transaction while holding the Class lock. Polymorphic source_id has no source FK;
SQL cascade alone cannot delete LearningSignals. For an already completed trusted deletion, the Class rebuild
cleans orphaned signals and removes stale mastery (or falls back to remaining Quiz evidence). Reference-file
cleanup alone never changes numeric evidence.

Large Classes hold the write lock for the rebuild duration; schedule operator runs accordingly. No scheduler,
background batching policy or production rebuild is introduced here.

## API compatibility

Existing Tutor mastery items and Tutor Progress `skill_mastery: [{skill_topic_id,name,level}]` automatically
read updated persisted mastery. No DTO changes or redundant fields/endpoints are added. No-evidence nulls,
real zero and ownership/member authorization retain their existing meanings.

Assignment-backed combined mastery influences existing Quiz recommendation ranking. Candidate eligibility,
prerequisite DAG/ranking rules, `activity_type: QUIZ`, FE-9 expectations and LMS-7 resume behavior are unchanged.
Assignment recommendation support is a separate coordinated backend/frontend follow-up.

## Migration policy

Current main contains V1–V14 (V14 creates file Assignments). This branch retains V17 unchanged, including
its original checksum 970127172. The combined set is V1–V14,V17: 15 files. V17 already supports ASSIGNMENT_RESULT,
SUBMISSION, UUID source identity, uniqueness and unrestricted numeric mastery precision. No new migration or
index was needed. Unmerged PRs reserve no migration versions. V17's original opening rollout comments describe
an older allocation snapshot and do not override this policy; migration bytes remain immutable.

Upgrade tests exercise V13 and current-main V14 baselines, preserving their checksums and validating the exact
combined version set. Before deployment inspect real target Flyway history, especially any environment that
already applied V17 without main's V14. Do not repair/reset history or rewrite applied files.

## Verification

Use JDK 25 and PostgreSQL 18.6 in an isolated container/database. Every command explicitly supplies DB_URL,
DB_USERNAME and DB_PASSWORD; never let tests fall back to lms_instance_dev. In this environment the Maven wrapper
below selects the installed JDK 25, local cache and trust configuration.

```bash
docker run -d --name lms9-phase6-pg -p 127.0.0.1:55436:5432 \
  -e POSTGRES_HOST_AUTH_METHOD=trust postgres:18.6 -c max_connections=300
docker exec lms9-phase6-pg createdb -U postgres lms9_phase6_test
export DB_URL=jdbc:postgresql://localhost:55436/lms9_phase6_test
export DB_USERNAME=postgres
export DB_PASSWORD=''

/workspace/.setup/mvn -B -Dtest=TopicAssignmentEvidenceReaderPostgresTest,MasteryCalculatorTest,MasteryPersistencePostgresTest,AssignmentPostgresHttpTest,Lms9AdaptivePostgresHttpTest,TopicQuizEvidenceReaderPostgresTest,RecommendationPostgresTest,RecommendationRankerTest,LearningProgressPostgresHttpTest,Lms7ProgressEndpointsTest,AssignmentMigrationPostgresTest,MasteryMigrationPostgresTest,MigrationPolicyTest test
docker exec lms9-phase6-pg createdb -U postgres lms9_phase6_verify
DB_URL=jdbc:postgresql://localhost:55436/lms9_phase6_verify DB_USERNAME=postgres DB_PASSWORD='' /workspace/.setup/mvn -B clean verify
```

New PostgreSQL coverage includes eligibility-before-limit, UUID ties, multiple topics/attachments, percentage
rounding, all historical policy changes, full hierarchy isolation, latest-three and independent components,
DECIMAL128/boundaries, zero/no evidence, canonical grade/regrade, remapping, source-deletion cleanup, idempotency,
Class isolation, real rollback injection, multi-pair rebuild retry and concurrent grading/remapping. Schema-invalid
null graded fields are tested through constraint rejection rather than disabling constraints.
HTTP tests cover Assignment-backed Tutor mastery/Progress shapes, unchanged authorization, changed Quiz ranking
through combined mastery, and actual ungraded upload/replacement producing no mastery.

### Executed results (2026-11-15 UTC)

JDK 25.0.5 and isolated PostgreSQL 18.6 were used; no shared database was targeted.

| Final command/suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Focused command above | 269 | 0 | 0 | 0 |
| Full clean verify (fresh lms9_phase6_verify database) | 1553 | 0 | 0 | 0 |
| TopicAssignmentEvidenceReaderPostgresTest | 41 | 0 | 0 | 0 |
| MasteryCalculatorTest | 43 | 0 | 0 | 0 |
| MasteryPersistencePostgresTest | 17 | 0 | 0 | 0 |
| AssignmentPostgresHttpTest | 29 | 0 | 0 | 0 |
| Lms9AdaptivePostgresHttpTest | 40 | 0 | 0 | 0 |
| TopicQuizEvidenceReaderPostgresTest | 31 | 0 | 0 | 0 |
| RecommendationPostgresTest / RecommendationRankerTest | 50 | 0 | 0 | 0 |
| LearningProgressPostgresHttpTest / Lms7ProgressEndpointsTest | 11 | 0 | 0 | 0 |
| AssignmentMigrationPostgresTest / MasteryMigrationPostgresTest / MigrationPolicyTest | 7 | 0 | 0 | 0 |

Full verification completed at 01:53:37 UTC, built/repackaged the application JAR, and reported BUILD SUCCESS.
Results above are executions from this implementation task, not previously saved CI reports.

Earlier diagnostic executions used the same explicit isolated test DB variables:

```bash
/workspace/.setup/mvn -B -DskipTests compile
/workspace/.setup/mvn -B -Dtest=MasteryCalculatorTest,MasteryPersistencePostgresTest,AssignmentPostgresHttpTest,Lms9AdaptivePostgresHttpTest,MigrationPolicyTest,AssignmentMigrationPostgresTest test
/workspace/.setup/mvn -B -Dtest=TopicAssignmentEvidenceReaderPostgresTest test
/workspace/.setup/mvn -B -Dtest=TopicAssignmentEvidenceReaderPostgresTest,Lms9AdaptivePostgresHttpTest test
```

Compile succeeded; the initial baseline passed 130 tests. The first reader-only execution had 30 tests and
three fixture errors (deletion order for answer/mapping foreign keys); fixture cleanup was corrected. The
subsequent reader/API run passed 80 tests. The first broad regression exposed two obsolete mastery migration
expectations (old migration count and combining an unmerged V14 allocation); the upgrade tests now compare
only actual main plus V17. These were test integration issues, not Assignment production calculation defects
or unresolved environment failures. The final focused and full runs pass with the corrections.
