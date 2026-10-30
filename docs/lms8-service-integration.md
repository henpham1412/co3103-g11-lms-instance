# LMS-8 service integration

The service logic is implemented against the reviewed UUID entities and repositories.
The feature branch still has an empty `ClassRepository`, empty Class/Chapter models,
and no Lesson/Activity/Question hierarchy or local authorization service. Do not
register these services as runtime beans until the real adapters below exist.
They intentionally have no `@Service` annotation: a permissive adapter, no-op lock,
or in-memory production hierarchy would bypass required behavior. No such adapter
is included. This also avoids adding unsatisfied component dependencies to startup.

## Required adapters and bean wiring

* `service/adaptive/integration/ClassAccess`: LMS-2/3 must resolve real Class UUIDs,
  enforce current-user local access/ownership/lifecycle permissions, and acquire a
  database class-row write lock held until the surrounding transaction completes.
  `ClassRepository` has no mapped entity or usable lock method yet. All topic,
  graph, content/question association writers and relevant upstream hierarchy
  mutations must coordinate on this same class lock. Do not substitute a JVM lock.
* `service/adaptive/integration/LearningResourceScope`: LMS-4/5 must supply real
  Lesson -> Chapter -> Class, Activity -> Lesson -> Class, and Question -> Quiz ->
  Activity -> Class resolution, plus the complete current question-ID set of a Quiz
  Activity. Resource IDs are UUIDs. Missing records must throw; no default class or
  empty question set for unavailable upstream modules is permitted.
* Once those adapters exist, register `SkillTopicService`,
  `SkillPrerequisiteService`, and `SkillTopicAssociationService` as normal Spring
  beans with constructor injection. Call them through Spring transaction proxies;
  direct `new` calls do not activate `@Transactional`. Do not use self-invocation to
  enter a transactional method. No bean-registration configuration is included yet.

## Behavior and transaction boundaries

* SkillTopic create/update use the Entity's normalization and Jakarta validation,
  including non-null name and maximum length 150. Blank-name rejection is not added
  without an agreed rule. Names remain case-sensitive; metadata update replaces all
  editable fields, allowing null description and difficulty. Enum conversion and
  auditing remain owned by the existing Entity/converter/listener.
* Every write requires ClassAccess's real class lock/authorization. For topic
  updates/deletes, a scalar repository projection determines the class before the
  managed entity is loaded. Duplicate-name checks complement the database UNIQUE
  constraint; restrictive FKs remain the final protection for history references.
* Topic deletion rejects any known prerequisite or Lesson/Activity/Question mapping;
  it never detaches or cascades them. Future LMS-9 references must also have restrictive
  FKs and be added to explicit service checks when those repositories exist.
* Prerequisite replacement validates the entire class graph with the existing
  SkillPrerequisiteValidator before writing, removes only obsolete outgoing edges,
  and inserts only new outgoing edges. Empty input clears outgoing prerequisites.
  Persistence failures propagate so the enclosing transaction rolls back.
* Association replacement validates every requested topic before writing. Null or
  duplicate IDs are rejected. Empty input clears mappings; unchanged links retain
  their identity. Old/new topic sets are returned for future LMS-9 integration, but
  this step does not create signals or calculate mastery/recommendations.
* Repositories and services do not implement HTTP errors or JSON handling. The
  existing GlobalExceptionHandler is empty; this step does not invent an HTTP error
  contract. Existing validator-style IllegalArgumentException is used for invalid
  inputs, IllegalStateException for reference/state conflicts, Jakarta
  EntityNotFoundException for missing topics and ConstraintViolationException for
  Entity validation. Upstream adapter authorization failures propagate unchanged.

## LMS-5 integration (not a replacement Question workflow)

LMS-5 owns Question CRUD, quiz locking, grading and attempts. It must acquire the
same class lock before its mutations and use the canonical QuestionSkillTopic table.
After persisting a Question, call `replaceQuestionTopics` through the Spring proxy
inside that Question transaction. Its MANDATORY propagation prevents a new,
independent Question association transaction. The Question must already appear in
the adapter's complete Quiz question-ID query. Omitted `skill_topic_ids` in an
upstream update means do not call replacement; an explicit empty list clears it.

Question replacement flushes canonical mappings, queries all current Quiz question
mappings, and stores their deduplicated union as the Quiz Activity's topics. After
permitted Question deletion, LMS-5 must remove that Question's mappings in its own
transaction and call `synchronizeQuizTopics`. Removing a topic from one Question
does not remove it from the Activity while another Question still references it.
Direct Tutor replacement of Quiz Activity topics is rejected; Assignment Activity
topics remain independently editable. Read operations derive Quiz topics from
Questions rather than trusting potentially stale Activity mappings.

The existing experimental assessment branch uses Integer IDs and different table
names. It is not a compatible adapter for this UUID contract without upstream work.

## Verification limits

Focused tests use mocked adapters/repositories and actual Bean Validation. Transaction
proxy tests verify commit/rollback and MANDATORY behavior, not PostgreSQL isolation.
Real rollback/locking races, repository queries, auditing and Flyway integration
require upstream UUID tables plus PostgreSQL. Existing V2-V5 migrations reference
missing parent tables; this service change does not edit or reorder migrations.

Executed on 2026-10-30 with JDK 25.0.4.1 and Maven 3.9.12 (session proxy,
truststore and dependency-cache options supplied outside repository configuration):

```text
mvn -Dtest=SkillTopicServiceTest,SkillPrerequisiteServiceTest,SkillTopicAssociationServiceTest,AdaptiveServiceTransactionTest,SkillPrerequisiteValidatorTest test
```

Result: 59 tests passed, zero failures/errors/skips (43 new service/transaction
tests and 16 existing validator tests). `mvn clean verify` was also attempted;
the four existing Spring application tests errored because PostgreSQL at
localhost:5432 refused the connection. Full verification did not pass. That run
preceded the final two transaction tests, which subsequently passed in the focused
suite. No database rollback, concurrency or migration success is claimed.
