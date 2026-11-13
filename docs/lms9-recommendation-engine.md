# LMS-9 Phase 4: internal recommendation engine

Phase 4 implements internal Quiz recommendation selection. D-10 through D-13
are coordinator-authorized working policies for this implementation, not a claim
of formal whole-group approval. Phase 1-3 calculation, historical D-08 eligibility,
persistence, synchronization and migrations remain unchanged. No public endpoint,
response DTO, frontend, Assignment integration or Issue closure is included.

## Responsibilities and upstream contracts

- `RecommendationService.recommend(classId, studentId)` is the internal boundary.
  It requires local ACTIVE STUDENT access and canonical LMS-3 ACTIVE membership;
  it resolves the actual ClassMember itself. An archived Class returns no new
  action. A future API must supply the authenticated subject, not an arbitrary
  client-supplied learner identity.
- `EligibleActivityReader` performs batched scalar reads of the actual LMS-4
  Activity → Lesson → Chapter → Class hierarchy, Quiz definitions, that member's
  attempts, CompletionMarks and LMS-8 associations. It does not call Tutor-only
  topic management operations or load an ORM content/answer graph.
- `TopicPrerequisiteResolver` reads class topics and outgoing edges in two queries.
  Both endpoints must belong to that class. The existing LMS-8
  `SkillPrerequisiteValidator` rejects cycles, missing endpoints and cross-class
  edges. Iterative traversal visits each ancestor at most once per requested
  target, including diamonds; no recursive SQL or query-per-node traversal.
- `RecommendationRanker` is a pure selection component over prepared candidates,
  persisted mastery and the prerequisite graph. It performs no retrieval, grading,
  recalculation or other mutation.

## Candidate eligibility and supported actions

Every returned candidate is a Quiz Activity with an actual Quiz configuration,
inside the requested ACTIVE Class, with ACTIVE membership and local STUDENT access.
Activity, Lesson and Chapter must all be PUBLISHED. Class equality follows the
complete parent chain and the actual membership, not unrelated foreign keys.
Foreign-class topic associations are never exposed or used for adaptation.

The current domain has only QUIZ and ASSIGNMENT Activity types. Assignment remains
deferred until canonical LMS-6 functionality exists. Materials and Lessons are
not invented Activity types. A missing Quiz configuration, empty Quiz or invalid
Question/options definition excludes that Quiz.

Two small LMS-5 policy extractions are shared by the existing source services and
the reader, without changing valid source behavior:

- `QuizAttemptEligibility`: original start/resume decision, inclusive opening time,
  exclusive deadline, every attempt consuming a slot, existing-attempt expiry and
  remaining-attempt limit. Original time-limit/deadline calculation is shared too.
- `QuizQuestionRules`: existing supported question types, nonblank content,
  representable nonnegative points/deductions, option cardinality and correct-option
  requirements. Individual option content/order checks remain enforced. Zero
  possible points remain allowed by LMS-5 definition rules; Phase 1-3 independently
  exclude zero-denominator assessment evidence.

The selector returns only quizzes for which the shared decision permits **START**.
An active existing attempt is handled exclusively by LMS-7/LMS-5 resume behavior;
it is not another new recommendation action. An expired IN_PROGRESS attempt blocks
a new start until the existing canonical expiry flow processes it. Recommendation
generation never submits, grades, expires, starts or otherwise modifies an attempt.
Pending-grade SUBMITTED attempts still consume slots.

Explicit `ActivitySkillTopic` associations are consumed directly and supplemented
by `QuestionSkillTopic` associations for Quiz Questions. Their union is deduplicated
by Activity/topic identity, including when both sources contain the same link.
Question mappings therefore work even without a materialized Activity mapping.
Source provenance does not introduce a new numeric ranking criterion beyond D-13.
Only genuine associations provide adaptive relevance; titles, descriptions,
Lesson mappings and topic difficulty never imply Activity relevance/difficulty.
An unmapped but otherwise eligible Quiz may still appear in normal sequence.

## Exact adaptive ranking

1. Consider eligible practice with persisted WEAK mastery, then DEVELOPING mastery.
   Missing mastery is not WEAK; an actual score of zero is WEAK.
2. Within each level, choose among topics with no other practice-eligible topic of
   that level in their complete prerequisite ancestry. This is the topological
   frontier: ancestors precede dependents even across intermediate topics without
   available practice. An ancestor without suitable practice does not block access
   or prevent fallback/dependent practice. The primary WEAK-before-DEVELOPING rule
   still applies when a DEVELOPING prerequisite has a WEAK dependent.
3. On that frontier choose the lower full-precision persisted score.
4. For equal priority, choose earlier Chapter.sequence_order, Lesson.sequence_order,
   then Activity.sequence_order.
5. Use ascending Activity UUID bytes as the final Activity tie-break. The Java
   comparison uses canonical UUID strings, matching PostgreSQL's unsigned UUID
   ordering, including the high-bit boundary. An internal topic UUID tie-break
   stabilizes the reason when one Activity reinforces several equal-priority topics.

A multi-topic Activity is represented once and may satisfy any of its genuine
reinforcement needs. The result includes the selected reinforcement topic internally.
The frontier approach avoids a non-transitive pairwise prerequisite comparator.
No mastery score is recalculated or rounded by recommendation generation.

## Normal learning sequence and progress

When no adaptive candidate exists, fallback is first-class:

1. Prefer eligible new Quiz actions without a submitted attempt and without an
   exclusively SUFFICIENT topic set. This allows progression instead of unnecessary
   reinforcement of a sufficient topic.
2. If none exist, retain eligible actions without an exclusively SUFFICIENT topic
   set, including canonically permitted retries.
3. If none exist, retain all eligible candidates, including sufficient-topic quizzes.
4. Within the selected set, prefer Activities in Lessons without an explicit
   CompletionMark, then follow the canonical hierarchy sequence and Activity UUID.

These steps interpret “next” using existing lesson-level progress and assessment
state; they do not introduce an Activity completion flag. A submitted Quiz may be
retried when canonical rules permit it. A completed Lesson does not make an
unattempted Quiz complete or exclude it permanently. Relevant adaptive practice
may be selected in a completed Lesson. Completion never creates numeric mastery.

No candidate produces `activity = null`, `NO_ELIGIBLE_ACTIVITY`. An archived Class
produces `activity = null`, `CLASS_ARCHIVED`. Access failures are denied using the
existing service exception conventions. Other internal reasons are
`ADDITIONAL_PRACTICE` and `NORMAL_SEQUENCE`; these are not new public response fields.

## Read-only snapshot, performance and API integration boundary

The service establishes a read-only REPEATABLE_READ transaction so visibility,
attempt state, mappings, prerequisite edges and persisted mastery come from one
PostgreSQL snapshot. Call it as a read boundary, not inside a pre-existing write
transaction with weaker isolation. The reader uses four batch queries independent
of candidate/topic count; closed new-start windows are discarded before loading
definitions/history. The graph uses two queries, and mastery uses one member query;
access/context reads are also constant in number. No per-candidate repository calls,
recursive queries, scheduler, cache, source locks or new migration/index are added.

The result is advisory at its read snapshot. The canonical source operation must
always recheck current access, availability and attempt limits when the learner
acts; recommendation is never an access grant or reservation.

LMS-7 `StudentProgressService.getResumeTarget` remains unchanged: in-progress Quiz,
permitted unfinished last visit, first incomplete Lesson, then class outline.
Phase 5 must decide how the existing resume target and this independent next-action
recommendation are presented/orchestrated. This engine does not decide their API
precedence, expose a resume endpoint or alter LastVisit/CompletionMark state.

## Verification

- `RecommendationRankerTest`: level/score/prerequisite/transitive ranking, sufficient
  progression, sequence, UUID high-bit ties, multi-topic selection, no evidence,
  no practice/no candidate, completion/retry fallback and DAG validation.
- `RecommendationPostgresTest`: real PostgreSQL 18 and canonical Flyway schema;
  controlled learner profiles, association sources/deduplication, full visibility
  hierarchy, archived history, opening/deadline, canonical retry/exhaustion/resume,
  pending grades, active/local role enforcement, class/member isolation, stable
  ordering, invalid definitions and read-only behavior. Fixtures roll back.
- `QuizAttemptEligibilityTest`: exact time boundaries, remaining slots, resume
  precedence, expiry and invalid configuration; existing LMS-5 regressions protect
  the source service behavior after the shared policy extraction.
- Existing LMS-3/4/5/7/8 and Phase 1-3 suites, full clean verify and migration upgrade/
  checksum validation are run for delivery. No existing migration was edited.

Remaining: API contracts/authenticated-subject orchestration, resume presentation,
canonical LMS-6 Assignment evidence/candidates when available, and project evaluation.
D-02/D-07 assumptions and the Phase 3 D-14 shared migration rollout gate remain intact.
