# LMS-9 Phase 2B: core Quiz evidence reader

Status: implemented for review; historical eligibility is not finalized.

`TopicQuizEvidenceReader.readLatestGradedCandidates(classId, classMemberId, skillTopicId)`
returns at most three internal evidence values containing the attempt, member,
topic, original submission timestamp and calculated topic score. It is not a
Spring bean and has no application callers, API, persistence writes, recalculation
hooks or recommendation integration. It is not an authorization boundary: future
callers must establish permission separately and supply trusted scope IDs.

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

## Decisions still pending

- **D-02:** UUID descending is a deterministic provisional secondary key, awaiting
  confirmation. It is not a newly approved business ordering rule.
- **D-07:** topic-score HALF_UP remains the explicitly provisional Phase 1 assumption.
- **D-08:** historical visibility, class archival, inactive membership/local access
  changes, invalidation and deletion eligibility remain unresolved. This reader
  deliberately supplies core graded candidates, not policy-approved historical
  evidence. Do not wire it into application behavior before D-08 is settled.
  Approved future policy predicates must run BEFORE LIMIT; filtering these three
  results afterward could incorrectly omit an eligible fourth result.

Historical hiding/archive, inactive membership/local suspension and invalidation/
deletion policy tests are deferred pending approval. Core tests neither assert
nor approve those historical-policy outcomes. Mapping changes are tested as fresh
reads of canonical current mappings; no historical mapping snapshot or mastery
recalculation is introduced.

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

The EXPLAIN test executes the actual aggregate statement with ANALYZE and BUFFERS
on 240 attempts with two mapped Questions. On PostgreSQL 18.6, the focused run on
2026-11-12 measured 4.026 ms execution and 4.736 ms planning. This is a small-fixture
measurement, not production performance evidence. No additional index or migration
is justified by that measurement. If larger representative workloads require an
index, inspect the query plan and follow D-14 allocation checks before proposing
any migration version. No version is allocated here.
