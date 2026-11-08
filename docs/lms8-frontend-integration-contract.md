# LMS-8 Frontend Integration Contract — Skill Topics, Prerequisites & Resource Associations

This document describes the **actual implemented API contracts** for **LMS-8** (`feat/lms-8-prerequisite-validation-phase-1` / PR #26) in `co3103-g11-lms-instance`, synchronized with canonical `main` (`LMS-2`, `LMS-3`, `LMS-4`, `LMS-5`, `LMS-7`).

---

## 1. Authentication & Authorization Rules

All LMS-8 endpoints require a valid RS256 Bearer access token (`Authorization: Bearer <access_token>`) issued by Central Identity (`sub` = user UUID) and an active local `lms_user_access` record.

### Access Control Policy (`DOC-05` §4.5.3)
- **Active Local Account Requirement:** Caller must have `access_status = ACTIVE` in `lms_user_access`.
- **Read Operations (`GET`):**
  - Permitted for the **owning Tutor** (`lms_role = TUTOR` and `class.tutor_id == jwt.sub`) on both `ACTIVE` and `ARCHIVED` classes.
  - Permitted for **enrolled Students with `ACTIVE` membership** (`lms_role = STUDENT` and `class_member.status = ACTIVE`) on both `ACTIVE` and `ARCHIVED` classes.
  - Non-owner Tutor, Student with `PENDING` / `INACTIVE` / `REJECTED` / no membership, Administrator, or suspended user receives `403 Forbidden` (`CLASS_ACCESS_DENIED`).
- **Write Operations (`POST`, `PATCH`, `DELETE`, `PUT`):**
  - Restricted strictly to the **owning Tutor** (`lms_role = TUTOR` and `class.tutor_id == jwt.sub`).
  - Acquire a pessimistic write lock (`SELECT ... FOR UPDATE`) on the owning `class` row for the duration of the transaction.
  - Permitted **only** when `class.status == ACTIVE`.
  - If `class.status == ARCHIVED`, write operations return `409 Conflict` (`RESOURCE_STATE_CONFLICT`).

---

## 2. Resource Hierarchy & Scoping Rules

Every resource in LMS-8 belongs to exactly one `Class`:
- `SkillTopic` $\rightarrow$ `Class` (`skill_topics.class_id`)
- `Lesson` $\rightarrow$ `Chapter` $\rightarrow$ `Class`
- `Activity` $\rightarrow$ `Lesson` $\rightarrow$ `Chapter` $\rightarrow$ `Class`
- `Question` $\rightarrow$ `Quiz` $\rightarrow$ `Activity` $\rightarrow$ `Lesson` $\rightarrow$ `Chapter` $\rightarrow$ `Class`

**Same-Class Invariant:**
- Prerequisite edges (`SkillTopic` $\rightarrow$ prerequisite `SkillTopic`) must connect two topics within the **same `Class`**. Cross-class prerequisites are rejected with `400 Bad Request` (`PREREQUISITE_CLASS_MISMATCH`).
- Resource-to-topic associations (`Lesson`, `Activity`, `Question`) must reference `SkillTopic` IDs belonging to the **same `Class`** as the resource. Cross-class topic associations are rejected with `400 Bad Request` (`RESOURCE_CLASS_MISMATCH`).

---

## 3. Standard Error Envelope

All error responses use `Content-Type: application/json` with the standard `DOC-05` 3-field envelope:

```json
{
  "code": "PREREQUISITE_CYCLE",
  "message": "Prerequisite graph must be acyclic",
  "details": []
}
```

### LMS-8 Error Codes Reference

| HTTP Status | `code` | When Returned |
|---|---|---|
| `400 Bad Request` | `INVALID_REQUEST` | Malformed JSON, invalid UUID path variable, or invalid request payload on non-PATCH endpoints |
| `400 Bad Request` | `VALIDATION_ERROR` | Invalid or empty `PATCH /api/v1/skill-topics/{skillTopicId}` payload (e.g., `{}` or unknown fields or invalid `difficulty_level`) |
| `400 Bad Request` | `INVALID_SKILL_TOPIC` | Bean validation failure when creating a `SkillTopic` |
| `400 Bad Request` | `PREREQUISITE_SELF_REFERENCE` | A topic is listed as its own prerequisite (`skill_topic_id == prerequisite_skill_topic_id`) |
| `400 Bad Request` | `PREREQUISITE_CLASS_MISMATCH` | A prerequisite topic belongs to a different `Class` than the target topic |
| `400 Bad Request` | `RESOURCE_CLASS_MISMATCH` | A `SkillTopic` associated with a `Lesson`, `Activity`, or `Question` belongs to a different `Class` |
| `400 Bad Request` | `INVALID_TOPIC_ASSOCIATION` | Null or duplicate `skill_topic_ids` / `prerequisite_skill_topic_ids` in a replacement list |
| `401 Unauthorized` | `UNAUTHORIZED` | Missing, malformed, or expired Bearer JWT |
| `403 Forbidden` | `FORBIDDEN` / `CLASS_ACCESS_DENIED` | Caller lacks active local access or is not the owning Tutor of the class |
| `404 Not Found` | `CLASS_NOT_FOUND` | Target `classId` does not exist |
| `404 Not Found` | `SKILL_TOPIC_NOT_FOUND` | Target `skillTopicId` (or one of the requested topic IDs) does not exist |
| `404 Not Found` | `LEARNING_RESOURCE_NOT_FOUND` | Target `lessonId`, `activityId`, or `questionId` does not exist |
| `409 Conflict` | `SKILL_TOPIC_NAME_DUPLICATE` | Normalized `name` already exists within the same `Class` |
| `409 Conflict` | `SKILL_TOPIC_REFERENCED` | Attempted to `DELETE` a `SkillTopic` that is still referenced by prerequisites, lessons, activities, or questions |
| `409 Conflict` | `PREREQUISITE_CYCLE` | Requested prerequisite replacement would introduce a direct or indirect cycle in the class prerequisite DAG |
| `409 Conflict` | `QUIZ_TOPICS_DERIVED` | Attempted `PUT /api/v1/activities/{activityId}/skill-topics` on a `QUIZ` activity (Quiz topics are derived automatically from its Questions) |
| `409 Conflict` | `RESOURCE_STATE_CONFLICT` | Attempted write on an `ARCHIVED` class or concurrent hierarchy state change |

---

## 4. Endpoints Specification

### 4.1. SkillTopic CRUD

#### `POST /api/v1/classes/{classId}/skill-topics`
Creates a new `SkillTopic` in the specified class.
- **Name Normalization:** Leading and trailing whitespace is trimmed (`"  Algebra  "` $\rightarrow$ `"Algebra"`). Must be non-blank and $\le 150$ characters after trimming.
- **Difficulty Level:** Required symbolic enum `"EASY"`, `"MEDIUM"`, or `"HARD"` (persisted in PostgreSQL as `SMALLINT` `1`, `3`, or `5`).

**Request Body:**
```json
{
  "name": "Linear Equations",
  "description": "Solving single-variable linear equations",
  "difficulty_level": "EASY"
}
```

**Response (`201 Created` + `Location: /api/v1/skill-topics/{skill_topic_id}`):**
```json
{
  "skill_topic_id": "20000000-0000-0000-0000-000000000001",
  "class_id": "10000000-0000-0000-0000-000000000001",
  "name": "Linear Equations",
  "description": "Solving single-variable linear equations",
  "difficulty_level": "EASY",
  "created_at": "2026-11-08T02:00:00Z",
  "updated_at": "2026-11-08T02:00:00Z"
}
```

---

#### `GET /api/v1/classes/{classId}/skill-topics`
Lists all `SkillTopic` items belonging to the class.

**Response (`200 OK`):**
```json
[
  {
    "skill_topic_id": "20000000-0000-0000-0000-000000000001",
    "class_id": "10000000-0000-0000-0000-000000000001",
    "name": "Linear Equations",
    "description": "Solving single-variable linear equations",
    "difficulty_level": "EASY",
    "created_at": "2026-11-08T02:00:00Z",
    "updated_at": "2026-11-08T02:00:00Z"
  }
]
```

---

#### `GET /api/v1/skill-topics/{skillTopicId}`
Returns details of a single `SkillTopic`.

**Response (`200 OK`):** Same object schema as above.

---

#### `PATCH /api/v1/skill-topics/{skillTopicId}`
Partially updates a `SkillTopic`'s metadata (`name`, `description`, `difficulty_level`).
- At least one field must be provided; `{}` returns `400 Bad Request` (`VALIDATION_ERROR`).
- Unknown fields (including immutable fields such as `skill_topic_id`, `class_id`, `created_at`, `updated_at`) are rejected with `400 Bad Request` (`VALIDATION_ERROR`).
- **Omitted vs `null` semantics:**
  - `name`: if provided, cannot be `null` or blank.
  - `difficulty_level`: if provided, cannot be `null` and must be `"EASY"`, `"MEDIUM"`, or `"HARD"`.
  - `description`: if omitted, existing description is preserved; if explicitly `"description": null`, description is cleared to `null`.
- Normalized no-op updates return `200 OK` without modifying `updated_at`.

**Request Body:**
```json
{
  "name": "Systems of Linear Equations",
  "description": null,
  "difficulty_level": "MEDIUM"
}
```

**Response (`200 OK`):**
```json
{
  "skill_topic_id": "20000000-0000-0000-0000-000000000001",
  "class_id": "10000000-0000-0000-0000-000000000001",
  "name": "Systems of Linear Equations",
  "description": null,
  "difficulty_level": "MEDIUM",
  "created_at": "2026-11-08T02:00:00Z",
  "updated_at": "2026-11-08T02:05:00Z"
}
```

---

#### `DELETE /api/v1/skill-topics/{skillTopicId}`
Deletes a `SkillTopic` if and only if it is not referenced by any `skill_prerequisites` (as target or prerequisite), `lesson_skill_topics`, `activity_skill_topics`, or `question_skill_topics`.
- If referenced anywhere, returns `409 Conflict` (`SKILL_TOPIC_REFERENCED`).

**Response (`204 No Content`):** Empty body.

---

### 4.2. Prerequisite Graph Management

#### `GET /api/v1/skill-topics/{skillTopicId}/prerequisites`
Lists the direct prerequisites of `{skillTopicId}`.

**Response (`200 OK`):**
```json
[
  {
    "skill_topic_id": "20000000-0000-0000-0000-000000000002",
    "prerequisite_skill_topic_id": "20000000-0000-0000-0000-000000000001"
  }
]
```

---

#### `PUT /api/v1/skill-topics/{skillTopicId}/prerequisites`
Atomically **replaces** the entire set of direct prerequisites for `{skillTopicId}`.
- Sending `{"prerequisite_skill_topic_ids": []}` removes all direct prerequisites of `{skillTopicId}`.
- Duplicate UUIDs or `null` elements inside `prerequisite_skill_topic_ids` are rejected (`400 Bad Request`).
- Validates the full class prerequisite graph using Kahn's topological sort algorithm before persisting:
  - Rejects self-dependency (`400 PREREQUISITE_SELF_REFERENCE`).
  - Rejects cross-class topics (`400 PREREQUISITE_CLASS_MISMATCH`).
  - Rejects direct (`A <-> B`) and indirect (`A -> B -> C -> A`) cycles (`409 PREREQUISITE_CYCLE`) and rolls back without modifying existing edges.

**Request Body:**
```json
{
  "prerequisite_skill_topic_ids": [
    "20000000-0000-0000-0000-000000000001"
  ]
}
```

**Response (`204 No Content`):** Empty body.

---

### 4.3. Learning Resource Associations (`Lesson` & `Activity`)

#### `GET /api/v1/lessons/{lessonId}/skill-topics`
Returns all `SkillTopic` items associated with `{lessonId}`.

**Response (`200 OK`):** Array of `SkillTopicResponse` objects.

---

#### `PUT /api/v1/lessons/{lessonId}/skill-topics`
Atomically replaces the `SkillTopic` set associated with `{lessonId}`.
- Passing `{"skill_topic_ids": []}` clears all topic associations for the lesson.

**Request Body:**
```json
{
  "skill_topic_ids": [
    "20000000-0000-0000-0000-000000000001",
    "20000000-0000-0000-0000-000000000002"
  ]
}
```

**Response (`200 OK`):**
```json
{
  "previous_skill_topic_ids": [
    "20000000-0000-0000-0000-000000000001"
  ],
  "current_skill_topic_ids": [
    "20000000-0000-0000-0000-000000000001",
    "20000000-0000-0000-0000-000000000002"
  ]
}
```

---

#### `GET /api/v1/activities/{activityId}/skill-topics`
Returns all `SkillTopic` items associated with `{activityId}`.
- If `{activityId}` is an `ASSIGNMENT`, returns its directly associated topics.
- If `{activityId}` is a `QUIZ`, returns the **derived union** of `SkillTopic` items across all `Question` items currently belonging to that Quiz.

**Response (`200 OK`):** Array of `SkillTopicResponse` objects.

---

#### `PUT /api/v1/activities/{activityId}/skill-topics`
Atomically replaces the `SkillTopic` set associated with an **`ASSIGNMENT`** activity.
- If `{activityId}` has `activity_type = QUIZ`, this endpoint returns `409 Conflict` (`QUIZ_TOPICS_DERIVED`) because Quiz topics are derived automatically from Question topic mappings.

**Request Body & Response (`200 OK`):** Same as `PUT /api/v1/lessons/{lessonId}/skill-topics`.

---

### 4.4. Question–SkillTopic Integration (F7 / Issue #13 via LMS-5 Endpoints)

There is **no** standalone `/api/v1/questions/{questionId}/skill-topics` write endpoint. Instead, Question–SkillTopic associations are written atomically inside LMS-5's Question creation, update, and deletion workflows:

1. **Create Question with Topics (`POST /api/v1/activities/{activityId}/questions`):**
   - Accepts optional `"skill_topic_ids": ["<uuid>", ...]` in `QuestionCreateRequest`.
   - When provided, LMS-5 persists the question and options, then invokes `SkillTopicAssociationService.replaceQuestionTopics(questionId, skillTopicIds)` inside the **same database transaction** (`Propagation.MANDATORY`) and automatically synchronizes the parent Quiz Activity's `activity_skill_topics` union.
   - If any `skill_topic_id` is missing (`404 SKILL_TOPIC_NOT_FOUND`), duplicated (`400 INVALID_TOPIC_ASSOCIATION`), or belongs to another class (`400 RESOURCE_CLASS_MISMATCH`), the entire Question creation transaction rolls back.
2. **Update Question Topics (`PATCH /api/v1/questions/{questionId}`):**
   - Accepts optional `"skill_topic_ids": ["<uuid>", ...]` in `QuestionUpdateRequest` (can be sent alone or alongside question content/points/options).
   - Omitting `skill_topic_ids` leaves existing question-topic mappings unchanged.
   - Providing `"skill_topic_ids": []` clears all topics for that question and recomputes the parent Quiz Activity's derived topic union.
   - Providing `"skill_topic_ids": null` is rejected with `400 Bad Request` (`INVALID_QUESTION`).
3. **Delete Question (`DELETE /api/v1/questions/{questionId}`):**
   - Automatically deletes the question's `question_skill_topics` rows and recomputes the parent Quiz Activity's derived topic union in `activity_skill_topics` within the same transaction.

---

## 5. Deferred Features (Out of Scope for LMS-8)

- **Mastery Estimation & Adaptive Recommendation (`LMS-9`):** Calculation of student topic mastery scores and adaptive next-step recommendations are owned by LMS-9. LMS-8 provides the complete `skill_topics`, `skill_prerequisites`, `lesson_skill_topics`, `activity_skill_topics`, and `question_skill_topics` data foundation and `TopicChange(previousTopicIds, currentTopicIds)` hooks for LMS-9.
- **Student Skill Graph Read Views:** Under `DOC-05` §4.5.3, LMS-8 topic and prerequisite management endpoints are restricted to the owning Tutor; student-facing mastery/graph views belong to LMS-9.

---

## 6. Frontend Readiness Summary

With LMS-3, LMS-4, LMS-5, LMS-7 merged on `main` and LMS-8 completed on PR #26, the following Frontend features/issues are unblocked for integration and review:
- **Tutor SkillTopic Management UI:** List, create, edit (`PATCH`), and delete class `SkillTopic` items with difficulty badges (`EASY`, `MEDIUM`, `HARD`).
- **Tutor Prerequisite Graph Editor UI:** View and replace (`PUT`) direct prerequisites per `SkillTopic` with cycle/self-reference error handling.
- **Tutor Lesson & Assignment Topic Tagging UI:** View and replace (`PUT`) `SkillTopic` tags on Lessons and Assignment Activities, and display derived `SkillTopic` tags (`GET`) on Quiz Activities.
- **Tutor Quiz Question Authoring UI (F7):** Attach and update `skill_topic_ids` when creating (`POST /api/v1/activities/{activityId}/questions`) or editing (`PATCH /api/v1/questions/{questionId}`) Quiz questions.
