# LMS-9 Phase 5: read APIs, authorization and resume coordination

This phase exposes the accepted Phase 1-4 Quiz mastery/recommendation implementation.
The Tutor summary response below is a coordinator-authorized working contract,
not a claim of formal whole-group approval. D-01 through D-13, including D-09's
missing-evidence policy, remain unchanged. DOC-01 through DOC-07 were not edited.

## Routes and JSON

All paths use the existing `/api/v1` base prefix. Both routes require the existing
validated Bearer JWT and ACTIVE local LMS access; global identity alone grants
no local role or class access. No entities or internal recommendation records are
serialized as API responses.

### Student recommendation

`GET /api/v1/classes/{classId}/recommended-activities`

`RecommendedActivitiesResponse` follows DOC-05 section 4.5:

```json
{
  "class_id": "class-uuid",
  "recommendation": {
    "activity_id": "activity-uuid",
    "activity_type": "QUIZ",
    "title": "Practice: Java Variables",
    "reason": "ADDITIONAL_PRACTICE",
    "skill_topics": [
      {"skill_topic_id": "topic-uuid", "name": "Java Syntax", "mastery_level": "DEVELOPING"}
    ]
  }
}
```

The controller derives the learner exclusively from the validated JWT subject.
No learner-selection parameter is defined. Additional query parameters do not
select another learner. `RecommendationService` remains the sole selection owner;
its canonical local role, ACTIVE membership, class lifecycle, full publication
hierarchy, Quiz window/configuration and attempt eligibility checks remain intact.
`activity_type = QUIZ` follows the current engine's supported Quiz-only contract.
Neither Assignment functionality nor new Activity types are inferred.

Only actual topics of the selected candidate are mapped. Their current persisted
mastery is read in the same REPEATABLE_READ transaction as selection. No assessment
evidence is represented by `mastery_level: null`, never WEAK/UNKNOWN or a fabricated
score. Only the documented topic ID/name/level fields are exposed to the Student.

When no candidate exists:

```json
{"class_id": "class-uuid", "recommendation": null, "reason": "NO_ELIGIBLE_ACTIVITY"}
```

An archived Class returns HTTP 200 with a null recommendation and `CLASS_ARCHIVED`,
preserving the accepted Phase 4 no-new-action policy. This does not grant archived
learning actions or alter permitted history reads. A nonempty result carries its
reason inside `recommendation`; the redundant top-level reason is omitted.

### Tutor mastery

`GET /api/v1/tutor/classes/{classId}/members/{classMemberId}/mastery`

`TutorMasteryResponse` uses the coordinator-authorized working schema:

```json
{
  "class_id": "class-uuid",
  "class_member_id": "member-uuid",
  "items": [
    {"skill_topic_id": "topic-a", "name": "Syntax", "mastery_score": 0,
     "mastery_level": "WEAK", "evidence_available": true},
    {"skill_topic_id": "topic-b", "name": "Collections", "mastery_score": null,
     "mastery_level": null, "evidence_available": false}
  ]
}
```

Every SkillTopic belonging to the requested Class is relevant to its owning Tutor's
summary, including topics not currently associated with published content. Topics
from another Class are excluded, including malformed cross-class derived rows.
Items are ordered by topic name then topic UUID, without duplicates. Scores remain
BigDecimal values at persisted precision; this read path does not calculate or
round mastery. No LearningSignal, source identity or internal timestamps are exposed.

## Authorization and error conventions

- `AdaptiveLearningController` uses the existing local-role method security:
  STUDENT for recommendations and TUTOR for summaries. Tutor/Administrator cannot
  impersonate a Student or gain an implicit ownership override.
- `MasteryReadService.readForTutor` also checks local ACTIVE TUTOR status at the
  service boundary. It reuses LMS-3's `ClassMembershipService.requireOwningTutorRead`,
  which calls the existing canonical ownership guard, then resolves an ACTIVE
  ClassMember by both its UUID and the requested Class UUID.
- The owning Tutor may read an ACTIVE or ARCHIVED Class. Former/INACTIVE or PENDING
  members are not exposed through this endpoint. Stored historical evidence is
  retained; D-08 is not converted into current learning authorization.
- Missing/invalid/expired JWT: 401. Suspended/unprovisioned local access, wrong role,
  wrong owner or Student nonmembership: 403. Unknown Tutor class/member, foreign
  member UUID or non-ACTIVE member: 404 `CLASS_OR_MEMBER_NOT_FOUND`, matching the
  individual Tutor Progress convention. Unknown Student class: 404 `CLASS_NOT_FOUND`.
- Existing common error handling supplies `{code, message, details}`. No security
  filter, JWT validator, ownership override or global error-envelope behavior was
  changed. The added LMS-3 helper performs no writes or class-state transition.

## Shared mastery reader and LMS-7 integration

`MasteryReadService` implements one class/member-scoped topic → LEFT JOIN current
LearnerMastery query. It enforces equality through actual ClassMember and SkillTopic
class IDs, not separate foreign keys. Candidate mapping uses the same query with a
selected-topic filter. No per-topic query or recalculation is performed.

`AdaptiveLearningReadService` maps internal results into the two REST DTOs within
a read-only REPEATABLE_READ boundary. The existing engine and all source hooks are
unchanged. A source action must still recheck current eligibility when performed;
a recommendation is advisory, not an attempt reservation or permission grant.

`TutorProgressService.getMemberProgress` now consumes `readForTutor` instead of an
empty mastery placeholder. Its existing `skill_mastery` shape stays exactly
`[{skill_topic_id, name, level}]`. Missing evidence produces `level: null`. It does
not gain the dedicated summary's score/evidence fields. The list is empty only
when the Class has no topics. LMS-7 still owns completion counts, chapter/lesson
progress, assessment statuses/history and their original response fields.

## Resume precedence for frontend integration

The existing `GET /api/v1/classes/{classId}/resume-target` endpoint, response and
`StudentProgressService.resolveResumeTarget` are unchanged:

1. Available in-progress Quiz attempt.
2. Visible, accessible, unfinished most recently visited Lesson.
3. First incomplete visible Lesson in canonical hierarchy order.
4. Class outline / `NO_UNFINISHED_TARGET`.

The frontend should prioritize the valid unfinished resume target (QUIZ_ATTEMPT or
LESSON). When the canonical response has no unfinished target, it may present the
LMS-9 recommendation. If no recommendation exists, show the normal class outline.
The frontend must not replace a valid resume target with a new recommendation.

The backend deliberately keeps the two approved endpoint contracts separate. It
does not duplicate resume selection, return a combined response, modify LastVisit,
complete Lessons, start/submit/grade attempts or overwrite any resume state during
recommendation generation. No frontend implementation is included in this phase.

## Verification and remaining scope

`Lms9AdaptivePostgresHttpTest` uses the actual Spring HTTP server, real PostgreSQL
18/Flyway, RS256 signatures, an in-process public JWKS server and persisted local
users. It does not mock authentication or authorization. It covers Student access,
visibility, availability, limits, exact DTO shapes, no evidence, role/IDOR attacks,
JWT rejection and canonical resume invariance; Tutor ownership/member/history
access, zero/no-evidence/full-precision scores; canonical grading/regrading/topic
remapping; and unchanged LMS-7 progress/history fields with shared mastery data.
Cleanup deletes only each test's own UUID-scoped fixtures.

Commands run using Java 25 and disposable PostgreSQL 18 databases:

```sh
# With DB_URL, DB_USERNAME and DB_PASSWORD pointing to the disposable test database:
/workspace/.setup/mvn -B --no-transfer-progress \
  -Dtest=Lms9AdaptivePostgresHttpTest,TutorAndAssessmentHistoryServiceTest test
/workspace/.setup/mvn -B --no-transfer-progress \
  -Dtest=LearningProgressPostgresHttpTest,ClassServiceTest,ClassMembershipServiceTest,ClassControllerApiTest,ClassPatchContractHttpTest,ChapterServiceTest,LearningContentHttpTest,QuizContractPostgresTest,Lms8AdaptivePostgresHttpTest,MasteryPersistencePostgresTest,TopicQuizEvidenceReaderPostgresTest,MasteryCalculatorTest,RecommendationRankerTest,RecommendationPostgresTest,QuizAttemptEligibilityTest,MigrationPolicyTest,MasteryMigrationPostgresTest test
/workspace/.setup/mvn -B --no-transfer-progress clean verify
git diff --check
```

No migration/entity/calculation/ranking changes, repair, shared database reset,
frontend changes or Assignment integration were introduced. The existing V17/D-14
shared rollout gate remains applicable. Remaining work includes frontend consumption
with the documented resume precedence, canonical LMS-6 integration when available,
deployment coordination and project evaluation. Issue #14 is not marked complete.
