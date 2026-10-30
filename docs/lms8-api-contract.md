# LMS-8 Step 5 — API layer and code guide

## Contract and runtime status

This API layer extends DOC-05, which specifies `/api/v1`, UUID path IDs, snake_case
JSON, ISO 8601 timestamps and the `{code, message, details}` error envelope but does
not specify SkillTopic management routes. The eleven routes below come from the
approved Step 5 request. Request/response details are recorded here for integration.
Lists are unpaginated JSON arrays; no pagination metadata or filtering is invented.

**Not yet a runnable end-to-end feature.** The three Step 4 services have no Spring
bean registration, and ClassAccess/LearningResourceScope have no real adapters.
These controllers require those service beans by constructor injection; application
startup remains blocked until the real adapters, transactional service wiring and
upstream schema exist. There are no optional dependencies, fake production beans,
permissive authorization defaults or fallback workflows.

DOC-05 normally defines PATCH as a partial update. The user explicitly confirmed
this endpoint's narrower contract: **name and difficulty_level are required;
description omitted or null clears it**. It maps to the existing full-metadata
UpdateSkillTopicCommand without a read/merge operation in the controller. A
description-only PATCH is therefore invalid. This exception needs to be reflected
when the team incorporates this addition into DOC-05.

## Routes and success responses

Paths are relative to `/api/v1`. Every request passes the existing JWT security
filter. Read permission is delegated to ClassAccess.requireReadAccess; writes
require the owning Tutor and writable Class state through ClassAccess.lockForWrite.
Exact read eligibility must be supplied by the real access adapter; authentication
alone is insufficient. No Controller invents local role or membership checks.

| Method | Path | Input | Success |
| --- | --- | --- | --- |
| GET | `/classes/{classId}/skill-topics` | UUID | 200, array of SkillTopicResponse |
| POST | `/classes/{classId}/skill-topics` | CreateSkillTopicRequest | 201, SkillTopicResponse; Location `/api/v1/skill-topics/{id}` |
| GET | `/skill-topics/{skillTopicId}` | UUID | 200, SkillTopicResponse |
| PATCH | `/skill-topics/{skillTopicId}` | UpdateSkillTopicRequest | 200, SkillTopicResponse |
| DELETE | `/skill-topics/{skillTopicId}` | UUID | 204, no body |
| GET | `/skill-topics/{skillTopicId}/prerequisites` | UUID | 200, array of SkillPrerequisiteResponse |
| PUT | `/skill-topics/{skillTopicId}/prerequisites` | ReplacePrerequisitesRequest | 204, no body |
| GET | `/lessons/{lessonId}/skill-topics` | UUID | 200, array of SkillTopicResponse |
| PUT | `/lessons/{lessonId}/skill-topics` | ReplaceSkillTopicsRequest | 200, TopicAssociationChangeResponse |
| GET | `/activities/{activityId}/skill-topics` | UUID | 200, array of SkillTopicResponse; Quiz topics are derived |
| PUT | `/activities/{activityId}/skill-topics` | ReplaceSkillTopicsRequest | 200, TopicAssociationChangeResponse; Assignment only |

PUT on a Quiz Activity returns 409 QUIZ_TOPICS_DERIVED, determined by the service.
There is no standalone Question-topic write route, new Question CRUD or Question
read route in this addition. DOC-05's existing POST `/activities/{activityId}/questions`
and PATCH `/questions/{questionId}` remain owned by LMS-5. Its `skill_topic_ids`
field is optional: omitted leaves mappings unchanged, empty clears them. This
branch has no upstream Question Controller/DTO to extend; none is fabricated.

## DTOs and JSON

All DTOs are Java records under `dto/request/adaptive` or `dto/response/adaptive`.
Java component names use camelCase and explicit JsonProperty annotations produce
DOC-05 snake_case. Enum JSON values are names EASY, MEDIUM or HARD, not database
SMALLINT codes; the existing converter continues to own database mapping 1/3/5.

CreateSkillTopicRequest and UpdateSkillTopicRequest have the same transport fields:

```json
{"name":"Algebra","description":null,"difficulty_level":"MEDIUM"}
```

* `name`: NotBlank, Size(max=150). Transport length is checked before the Entity's
  existing trim normalization; clients should submit the intended name without
  padding. Name uniqueness remains a service/database concern and is case-sensitive.
* `description`: nullable, no invented length or non-null constraint.
* `difficultyLevel`: NotNull, using the existing TopicDifficulty enum unchanged.

The records are separate because create and update are distinct use cases, even
though their current fields match. They do not implement business rules.

ReplacePrerequisitesRequest:

```json
{"prerequisite_skill_topic_ids":["20000000-0000-0000-0000-000000000002"]}
```

ReplaceSkillTopicsRequest, reused for Lesson and Assignment:

```json
{"skill_topic_ids":[]}
```

Both list components have NotNull and `List<@NotNull UUID>` element constraints.
Empty is valid and clears the relevant set. Null lists/elements and malformed UUIDs
are 400; duplicate IDs remain rejected by the service, without duplicated graph or
association validation in DTOs. These simple inputs need no additional Commands.

SkillTopicResponse contains only the public metadata, never a JPA entity:

```json
{
  "skill_topic_id":"20000000-0000-0000-0000-000000000001",
  "class_id":"10000000-0000-0000-0000-000000000001",
  "name":"Algebra",
  "description":null,
  "difficulty_level":"MEDIUM",
  "created_at":"2026-10-30T00:00:00Z",
  "updated_at":"2026-10-30T01:00:00Z"
}
```

SkillPrerequisiteResponse exposes the edge directly, without an EmbeddedId wrapper:

```json
{"skill_topic_id":"20000000-0000-0000-0000-000000000001","prerequisite_skill_topic_id":"20000000-0000-0000-0000-000000000002"}
```

TopicAssociationChangeResponse converts immutable domain sets to explicit UUID
arrays sorted by UUID for deterministic output. Its constructor defensively copies
both lists. It reports the old/new mapping, not mastery or a learning signal:

```json
{"previous_skill_topic_ids":["20000000-0000-0000-0000-000000000001"],"current_skill_topic_ids":[]}
```

## A. Request flow

Client -> existing JWT filter -> Controller argument binding/Valid Request DTO ->
explicit mapper -> existing Command (create/update) -> transactional Service ->
real access/hierarchy adapter and Repository -> explicit Response DTO -> JSON.

For replacements, Controller forwards the validated UUID list directly; it does
not construct an artificial Command or manipulate Entity collections. Service
transactions, class locks, cycle validation and Quiz derivation stay in Step 4.
Controllers never call repositories, manage transactions or serialize JPA entities.

## B. Controllers and important methods

All controllers are RestController beans with final dependencies and one constructor;
Autowired is unnecessary. Calling the injected service must reach its Spring proxy
once Step 6 registers the real services.

| Class/method | Input -> output and steps | Important failures |
| --- | --- | --- |
| SkillTopicController.list | class UUID -> service.listByClass -> array of mapped topic DTOs | CLASS_NOT_FOUND, CLASS_ACCESS_DENIED |
| SkillTopicController.create | class UUID + valid request -> Create Command -> service.create -> DTO, 201 and Location | INVALID_SKILL_TOPIC, SKILL_TOPIC_NAME_DUPLICATE, Class access/state failures |
| SkillTopicController.get | topic UUID -> service.get -> mapped DTO | SKILL_TOPIC_NOT_FOUND, CLASS_ACCESS_DENIED |
| SkillTopicController.update | topic UUID + required metadata -> Update Command -> service.update -> mapped DTO, 200 | INVALID_SKILL_TOPIC, SKILL_TOPIC_NOT_FOUND, SKILL_TOPIC_NAME_DUPLICATE, Class access/state failures |
| SkillTopicController.delete | topic UUID -> service.delete -> 204 | SKILL_TOPIC_NOT_FOUND, SKILL_TOPIC_REFERENCED, Class access/state failures |
| SkillPrerequisiteController.list | topic UUID -> service.list -> edge DTO array | SKILL_TOPIC_NOT_FOUND, CLASS_ACCESS_DENIED |
| SkillPrerequisiteController.replace | topic UUID + validated prerequisite list -> service.replace -> 204; empty allowed | PREREQUISITE_SELF_REFERENCE, PREREQUISITE_CLASS_MISMATCH, PREREQUISITE_CYCLE, missing topic/access/state failures |
| SkillTopicAssociationController.getLessonTopics | lesson UUID -> service.getLessonTopics -> topic DTO array | LEARNING_RESOURCE_NOT_FOUND, CLASS_ACCESS_DENIED, RESOURCE_CLASS_MISMATCH |
| SkillTopicAssociationController.replaceLessonTopics | lesson UUID + validated topic list -> service.replaceLessonTopics -> old/new DTO | missing resource/topic, INVALID_TOPIC_ASSOCIATION, RESOURCE_CLASS_MISMATCH, Class access/state failures |
| SkillTopicAssociationController.getActivityTopics | activity UUID -> service.getActivityTopics -> topic DTO array; Quiz union remains owned by service | missing resource/topic, Class access, RESOURCE_CLASS_MISMATCH |
| SkillTopicAssociationController.replaceAssignmentTopics | activity UUID + validated topic list -> service.replaceActivityTopics -> old/new DTO | QUIZ_TOPICS_DERIVED, missing resource/topic, INVALID_TOPIC_ASSOCIATION, scope/access/state failures |

Common transport failures occur before service calls: invalid UUID, invalid enum,
malformed/missing JSON and DTO constraint violations yield 400 INVALID_REQUEST.
Controllers do not catch business exceptions or convert server errors to 400.

## C. Explicit mapper

SkillTopicApiMapper is a stateless utility, not another Spring dependency:

* `toCommand(CreateSkillTopicRequest)` forwards the three metadata fields to the
  existing CreateSkillTopicCommand. It leaves trimming/uniqueness to Step 4.
* `toCommand(UpdateSkillTopicRequest)` forwards to UpdateSkillTopicCommand,
  preserving null description so the service clears it as approved.
* `toResponse(SkillTopic)` copies the seven public fields including audit Instants.
* `toResponse(SkillPrerequisite)` unwraps the composite key into two UUID fields.
* `toResponse(TopicChange)` returns defensive, sorted old/new UUID lists.

No ModelMapper, MapStruct or new dependency is added. Services and Commands remain
independent of transport DTOs and ResponseEntity.

## D. Exception handling

GlobalExceptionHandler now extends ResponseEntityExceptionHandler so Spring MVC's
known binding, routing, method-validation, media-type and HTTP exceptions retain
their status and protocol headers. `handleExceptionInternal` replaces framework
detail bodies with the safe DOC-05 envelope, preserving headers such as Allow.
Errors explicitly use application/json, including 406 when the client's requested
success media type cannot be produced.

| Handler/path | Status/code |
| --- | --- |
| AppException | Existing ErrorCode status/message (400, 403, 404, 409, etc.) |
| Jakarta ConstraintViolationException | 400 INVALID_REQUEST |
| MVC invalid fields, missing query/body, malformed JSON/UUID | 400 INVALID_REQUEST |
| MVC missing route/resource | 404 RESOURCE_NOT_FOUND |
| Wrong HTTP method | 405 METHOD_NOT_ALLOWED, preserving Allow |
| Unsupported Accept | 406 NOT_ACCEPTABLE |
| Unsupported request Content-Type | 415 UNSUPPORTED_MEDIA_TYPE |
| Other framework HTTP exceptions | Original status, safe HTTP_ERROR envelope; unexpected 5xx uses INTERNAL_ERROR |
| AccessDeniedException/SecurityException reaching MVC | 403 CLASS_ACCESS_DENIED |
| Spring/Hibernate integrity constraint | 409 DATA_CONFLICT; known uq_skill_topics_class_name -> SKILL_TOPIC_NAME_DUPLICATE |
| Unexpected exception | 500 INTERNAL_ERROR; full cause logged on server only |

There is still one AppException type; no per-code exception hierarchy. Database
constraint failures are not confused with Jakarta Bean Validation. No raw exception
message, SQL, rejected body or stack trace is included in responses.

Security filter failures occur before Controller/MVC advice. Existing SecurityConfig
returns 401 with WWW-Authenticate for missing/invalid Bearer tokens, without the
DOC-05 JSON envelope in the tested missing-token case. This Step does not rewrite
the security filter configuration. Consistent filter-level error envelopes and real
local authorization remain integration work for the security owner at Step 6.

## Step 6 dependencies and verification

* LMS-2/3: real ClassAccess, UUID ClassRepository locking and local ACL/lifecycle.
* LMS-4/5: real LearningResourceScope and complete Quiz question-ID queries.
* Register all three Step 4 services as transactional Spring beans after real
  adapters exist; do not bypass constructors or manually instantiate in production.
* Coordinate parent-table migrations before V2–V5 and PostgreSQL startup. No
  Entity, Repository, existing migration or Service code changes are part of Step 5.
* LMS-5 must acquire the same Class lock before Question mutation, call
  replaceQuestionTopics through the proxy in its write transaction, remove mappings
  before deleting a Question under restrictive FKs, and call synchronizeQuizTopics.
  Existing MANDATORY hooks and the canonical QuestionSkillTopic entity are unchanged.
* Capture old topic associations before deletion for later LMS-9 integration; do
  not implement mastery/recommendation processing in this API layer.

AdaptiveControllersTest uses standalone MockMvc with mocked services. It covers
all eleven routes, response DTO JSON, Request-to-Command mapping, required fields,
empty/null ID collections, PATCH clearing semantics, business errors and absence
of a separate Question write route. GlobalExceptionHandlerTest covers business
codes, safe 500, MVC 400/404/405/406/415, integrity conflicts and Allow preservation.
AdaptiveSecurityFilterTest uses the actual SecurityConfig in a small MVC context
with test-only mocked service/decoder. It verifies the authentication boundary;
the jwt() helper does not verify signatures, JWKS, expiration or real local ACLs.
These tests do not prove PostgreSQL, database concurrency or upstream integration.

### Executed checks

Run on 2026-10-30 using JDK 25.0.4.1 and Maven 3.9.12, offline against the session
dependency cache. A command-line Mockito 5.23.0 javaagent was supplied to avoid
unsupported dynamic self-attachment in the sandbox; no build file was changed.
Session Maven settings and cache paths were also supplied outside the repository.

```text
mvn -o -Dtest=AdaptiveControllersTest,AdaptiveSecurityFilterTest,GlobalExceptionHandlerTest test
mvn -o verify
```

* API/handler/filter subset: **74 tests passed**, zero failures/errors/skips
  (39 Controller, 31 handler, 4 filter tests).
* Full verify: **140 tests executed, 136 passed, 4 ApplicationContext errors**.
  All existing prerequisite/service/transaction tests passed. The four existing
  application tests fail while Flyway connects to PostgreSQL with root cause
  `java.net.SocketException: Operation not permitted`; full verify did not pass.
  The run does not reach real schema migration or prove runtime service wiring.
* `git diff --check` passed. Entity, Repository, Migration, Service, Command,
  enum/converter and production SecurityConfig files are unchanged from baseline.
