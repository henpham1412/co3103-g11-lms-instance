# LMS-13: local user administration

Implements DOC-01 UC7.1, DOC-04 local access and AuditLog storage, and DOC-05
§§1.6–1.9 / 5.1. Requests require a valid Central Identity Bearer JWT and an
ACTIVE local ADMINISTRATOR. Authorization comes from the local database; token
role claims cannot grant access. Local updates never change Central Identity.

## Routes and compatibility

| Method | Primary route | Result |
| --- | --- | --- |
| GET | `/api/v1/admin/users` | Filtered, paginated local users |
| GET | `/api/v1/admin/users/{userId}` | One local user |
| PATCH | `/api/v1/admin/users/{userId}` | Updated local access |

All three routes also accept the `/api/admin/users` prefix. Both prefixes return
the same DOC-05 snake_case response and use **one-based pagination**. This
replaces the branch's earlier camelCase response and zero-based page convention;
clients using that earlier contract must update their response parsing and page
numbers. The original request field aliases remain supported as described below.

Successful responses are `application/json;charset=UTF-8`. Before service calls,
all routes check `Accept` against this writable representation. An explicit
`application/json;q=0` overrides positive wildcards; unsupported charsets or media
types without an acceptable UTF-8 JSON alternative return `406 NOT_ACCEPTABLE`.
Ordinary resource JSON is never labeled `application/problem+json`. Error
responses always use the standard JSON envelope, including negotiation failures.

## List and detail

| Query parameter | Contract |
| --- | --- |
| `q` | Case-insensitive local name/email search, or exact UUID; LIKE metacharacters are literal |
| `lms_role` | `STUDENT`, `TUTOR`, `ADMINISTRATOR`; legacy alias `role` |
| `access_status` | `ACTIVE`, `SUSPENDED`; legacy alias `status` |
| `page` | Positive integer, default `1` |
| `size` | Integer in `1..2000`, default `20`; larger values are rejected |
| `sort` | Allowed property plus optional `asc`/`desc`; repeat for multiple orders; default `created_at,desc` |

Allowed sorts: `user_id`, `full_name`, `email`, `lms_role`, `access_status`,
`last_login_at`, `created_at`, `updated_at`. Their existing Java-style aliases
(`userId`, `fullName`, `lmsRole`, etc.) also work. Unknown/nested properties and
invalid directions are rejected. Supplying a filter and its alias together is
ambiguous and returns `400 VALIDATION_ERROR`, even when both values are identical.

Supplied page values must be decimal integers in `1..2147483647` and size values
in `1..2000`; omitted values alone use defaults. Empty, repeated, negative, zero,
nonnumeric, or out-of-range values return `400 VALIDATION_ERROR`. The effective
offset `(page - 1) * size` cannot exceed `2147483647`. Invalid sorts and repeated
scalar query parameters also return `400 VALIDATION_ERROR`.

Example: `GET /api/v1/admin/users?page=1&size=12&sort=created_at,desc`:

```json
{
  "items": [{
    "user_id": "00000000-0000-0000-0000-000000000001",
    "full_name": "Example User",
    "email": "user@example.test",
    "lms_role": "STUDENT",
    "access_status": "ACTIVE",
    "last_login_at": null,
    "created_at": "2026-01-01T00:00:00Z",
    "updated_at": "2026-01-01T00:00:00Z"
  }],
  "page": 1,
  "size": 12,
  "total_items": 1,
  "total_pages": 1,
  "last": true
}
```

`last` is retained as additional metadata. An empty page beyond the matching
records is terminal even at the maximum page number. No matches return empty
`items`, zero totals, and `last: true`. Detail GET returns the same user object
without a pagination envelope. Name, email and last login are read-only local
display projections, not identity or profile editing inputs.

## Access updates and audit

```json
{"lms_role": "TUTOR", "access_status": "SUSPENDED"}
```

PATCH accepts either or both fields. Legacy body aliases are `role` and
`accessStatus`. Only exact enum names or null are valid; numbers, numeric strings,
booleans, arrays and unknown values produce `400 VALIDATION_ERROR`. Unknown fields,
read-only identity/profile fields, and simultaneous canonical/alias fields also
produce `400 VALIDATION_ERROR`, so misspellings cannot silently succeed.

Omitted/null fields retain their current values. `{}` and unchanged values are
accepted no-ops: they do not advance `updated_at` or create an audit event. A
successful change returns `200` with the full user projection. Missing bodies,
whole-body null, and malformed JSON return `400 BAD_REQUEST`.

An active administrator has both `ADMINISTRATOR` role and `ACTIVE` status. The
last active administrator cannot be demoted or suspended, including by themself.
Suspended administrators do not satisfy this invariant. Self-updates are allowed
when another active administrator remains.

The service transaction:

1. Takes bootstrap's singleton `GLOBAL` pessimistic database lock, then reloads
   the acting administrator and requires an existing ACTIVE ADMINISTRATOR.
   A missing, demoted, or suspended actor receives `403 FORBIDDEN` before any
   target access or audit write, including requests authenticated before waiting
   on the lock.
2. Loads and validates the target and transition, then flushes the access change so the
   response includes its updated timestamp.
3. Writes one `LMS_USER_ACCESS_UPDATED` event for an actual change, in the same
   transaction, and commits both records together.

Flyway **V4** creates `audit_logs`: `audit_log_id`, `actor_id`, `action`,
`entity_type`, `entity_id`, `details`, `created_at`. The actor is the authenticated
immutable UUID; the target is a `LmsUserAccess` UUID. JSON text in `details`
contains before/after `lms_role` and `access_status`, without profile data. The
actor foreign key restricts deletion to preserve history. An audit write failure
rolls back the access fields and timestamp. Rejected changes produce no success
event. No audit-management endpoint is introduced.

Concurrent removals serialize on the same lock and preserve one active admin.
Updates preserve bootstrap metadata; a missing GLOBAL row fails closed. Future
writers must honor the same locking order. See [administrator-bootstrap.md](administrator-bootstrap.md).

## Errors

```json
{"code":"LOCAL_USER_NOT_FOUND","message":"User not found","details":[]}
```

| HTTP | Code | Trigger |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | Invalid UUID, missing body, whole-body null, or malformed JSON |
| 400 | `VALIDATION_ERROR` | Invalid pagination, sort, ambiguous/repeated query, access enum, or unsupported/ambiguous PATCH field |
| 401 | `UNAUTHORIZED` | Missing or invalid JWT; includes `WWW-Authenticate: Bearer` |
| 403 | `FORBIDDEN` | Caller lacks active local administrator access |
| 404 | `LOCAL_USER_NOT_FOUND` | Target has no local record |
| 406 | `NOT_ACCEPTABLE` | No acceptable UTF-8 JSON response representation |
| 409 | `INVALID_ROLE_OR_STATUS_TRANSITION` | Transition would remove the last active administrator |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Unsupported request content type |
| 500 | `INTERNAL_SERVER_ERROR` | Unexpected infrastructure failure; no internal details exposed |

All error responses contain `code`, `message`, and `details` as required by
DOC-05 §1.8. Unrelated application validation retains its existing error mapping.

## Verification

Use JDK 25 and a **disposable PostgreSQL database**. Set `DB_URL`, `DB_USERNAME`,
and `DB_PASSWORD`; the test user needs schema-creation permission. Tests apply
Flyway migrations and use isolated schemas, including concurrency and failure
injection. Signed JWT/JWKS fixtures run locally; Central Identity is not required.

```powershell
./mvnw.cmd test
```

`AdminUserHttpRegressionTest` uses a real servlet server and request-owned
transactions. It verifies DOC-05 round trips, charset/media rejection before
commit, read-only projections, item authorization, exact domain codes,
pagination boundaries, audit content and database-induced audit rollback.
Deterministic PostgreSQL lock barriers verify queued requests cannot undo a
committed suspension/demotion or change another user, and only the revocation
is audited. Controller tests also prove invalid semantic queries and negotiation
never call the service. Service integration tests verify locking, concurrency, audit counts
and rollback after flush. The full suite must report zero failures/errors/skips.

The updated [Postman collection](postman/LMS-13_AdminUserManagement.postman_collection.json)
uses the versioned routes, snake_case fields, and one-based pages. Its default
origin is `http://localhost:8081`. Set `admin_jwt`, `student_jwt`, and
`target_user_id` locally. Use one ACTIVE administrator, a separate ACTIVE student
and a third disposable target. Run the collection in order: access updates leave
the target ACTIVE/TUTOR. Last-admin cases preflight the fixture and fail/skip the
mutation if it is unsuitable. Keep token values out of exported files.
