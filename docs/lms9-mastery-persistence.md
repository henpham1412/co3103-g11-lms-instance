# LMS-9 Phase 3: mastery persistence and source synchronization

Scope: Quiz evidence, current mastery and contextual Lesson completion. No
recommendations, new APIs, frontend, Assignment model/reader or Issue closure.
D-09 is the coordinator-authorized working policy, not a claim of whole-group
approval. D-02 UUID ordering, D-07 HALF_UP and the accepted D-08 working policy
remain unchanged. DOC-01 through DOC-07 were not modified.

## Persistence

- `LearningSignal`: UUID identity, ClassMember, SkillTopic, signal type/value,
  original event timestamp and polymorphic source type/UUID. Uniqueness is
  `(class_member_id, skill_topic_id, signal_type, source_type, source_id)`.
  Quiz signals use QUIZ_RESULT / QUIZ_ATTEMPT and original submitted_at.
  Completion signals use LESSON_COMPLETION / COMPLETION_MARK, value 1 (explicit
  mark exists, not numeric mastery), and the mark's original marked_at.
- `LearnerMastery`: UUID, ClassMember, SkillTopic, level, numeric score and update
  timestamp; one row per member/topic. No eligible assessment evidence means no
  row. An actual assessment score of zero still creates WEAK mastery.
- PostgreSQL NUMERIC has no fixed scale: the DECIMAL128 aggregate is stored without
  two-decimal display rounding. The database checks level/score consistency using
  the actual stored score. Per-attempt topic results remain two-decimal HALF_UP.
- Only derived records cascade when a membership/topic is physically deleted.
  No canonical assessment or progress history cascades through these new tables;
  existing source-retention restrictions remain. No new deletion API is provided.
- Source references are polymorphic as required by DOC-04, not foreign keys to a
  fabricated common source table. Synchronization validates actual class scope
  and reads canonical source records before writing derived state.

## Synchronization and calculation

`MasteryRecalculationService` reuses `TopicQuizEvidenceReader` and the unchanged
pure `MasteryCalculator`. The reader now also exposes all eligible results using
the same SQL/ordering without LIMIT, so every eligible source signal can be kept.
Only the first three results affect the Quiz component. The existing latest-three
reader method remains available and covered by its regressions.

Synchronization matches existing records by stable source identity, updates their
value/event time in place and deletes obsolete signals. It never accumulates
scores incrementally. Mastery is recalculated from canonical evidence, upserted
when present and deleted when absent. An unchanged result preserves its identity
and update timestamp. Removing an eligible source can promote the fourth result.

The Assignment enum/source vocabulary is represented in the schema, but no
Assignment producer or canonical reader exists. Current recalculation passes an
empty Assignment component to the calculator. A future canonical LMS-6 adapter
must supply that component before Assignment evidence production is enabled.
No Assignment integration is claimed here. Completion signals never enter either
assessment component or create/increase mastery.

## Source transaction boundaries

- `QuizGradingService.gradeAttempt`: Class → Quiz → Attempt, canonical grade, then
  synchronize only this learner's affected topics. A pending-grade retry also
  synchronizes an already complete result, without fabricating user authentication.
- `recalculateSubmittedAttempts`: hold Class/Quiz, regrade the whole submitted set,
  then synchronize once after every result is final.
- `QuizQuestionService.updateQuestion`: retain LMS-8 TopicChange old/new sets and
  pass their union to the grading coordinator. Mapping-only changes synchronize
  after mapping replacement; mapping plus grading changes synchronize after all
  regrading. The LMS-8 Question hook itself does not recalculate prematurely.
- `LessonCompletionService`: mark/unmark, flush source state, then synchronize that
  learner's contextual completion signals in the same transaction.
- `replaceLessonTopics`: synchronize contextual signals for members with marks in
  that Lesson after the replacement is flushed. Old topic signals are removed.

Source hooks require an existing transaction. The explicit internal
`recalculatePair(classId, memberId, topicId)` rebuild boundary starts/joins one.
Every entry locks the real Class row and validates scope before derived writes.
Source grading retains the Class → Quiz → Attempt/Question lock hierarchy.
Reacquiring an already held Class/Quiz lock does not acquire a different class.
No coordinator uses SecurityContext to authorize internal work. User-facing
authorization remains at existing source entry points.

JPA source changes are flushed before the aggregate JDBC read on the same Spring
transaction/connection. Class locking serializes canonical grading, mapping,
completion and repair work; uniqueness is also enforced by PostgreSQL. Failures
roll back grades, signals and mastery together within the grading transaction.
The pre-existing separate submission transaction can remain committed/pending and
be graded on retry. No broker, new worker or scheduler is introduced.

Storage changes outside canonical workflows do not magically trigger application
hooks. A trusted maintenance operation that deletes/invalidates source rows must
also rebuild its known affected pairs transactionally. There is no canonical
Quiz invalidation/cancellation API in this baseline, so none was invented.

## V17 allocation and rollout gate (D-14)

Observed on 2026-11-13 before creating V17:

- Live main: 2573f0d859c6c05050ffc9789eacdc70ef5d547f, V1-V13.
- Accessible lms_instance_dev history: successful V1-V13.
- Open PR #31: 30a62a05906828261c105584582ad375b4abf552, V14-V16.
- The open-PR search returned no other competing migration PR.

`V17__create_learning_signals_and_learner_mastery.sql` is the feature branch's
allocation based on those observed sources. Other shared/persistent histories
were unavailable, so this does NOT establish a globally safe deployment order.
No existing persistent/shared database was migrated by this task.

**Do not apply V17 to a shared/persistent environment until the integration owner
checks its actual history and confirms allocation. PR #31's V14-V16 must land and
be applied before V17 if that PR is retained.** Isolated branch tests permit the
V13 → V17 gap only to verify this branch without merging unrelated LMS-13 code.
If V17 has been applied persistently, preserve its version/content and resolve
later conflicts with forward migrations, not renumbering, repair or database reset.

`MasteryMigrationPostgresTest` verifies V13 upgrades and validation both standalone
and with exact V14-V16 test fixtures copied from the pinned PR #31 commit. Those
fixtures are under test resources, not production migrations. Each upgrade runs in
a private randomly named schema and leaves existing V1-V13 checksums unchanged.

## Verification coverage

Real PostgreSQL tests cover source submission/pending grading/automatic expiry,
stable signal identity and retries, older submissions graded later, all-history
signal storage versus latest-three calculation, fourth-result promotion, precision
near 80, no-evidence deletion, regrading, multi-topic mapping additions/removals,
combined answer-key/mapping changes, contextual completion/remapping/unmarking,
duplicate constraints, cross-class rejection, rollback/recovery and concurrent
regrading/mapping plus concurrent internal retries without an end-user context.
Existing LMS-5/7/8 PostgreSQL/HTTP tests continue exercising upstream contracts.
