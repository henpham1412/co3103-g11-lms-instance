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

Search text containing U+0000 (NUL), including `q=%00` and `q=a%00b`, returns
`400 VALIDATION_ERROR` before repository access. The controller, service, and
specification factory share this validation. Literal text `%00` remains searchable
by encoding the percent sign (`q=%2500`); values are not decoded twice.

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

Flyway **V20** creates `audit_logs`: `audit_log_id`, `actor_id`, `action`,
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
| 405 | `METHOD_NOT_ALLOWED` | Unsupported resource method, including TRACE |
| 406 | `NOT_ACCEPTABLE` | No acceptable UTF-8 JSON response representation |
| 409 | `INVALID_ROLE_OR_STATUS_TRANSITION` | Transition would remove the last active administrator |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Unsupported request content type |
| 500 | `INTERNAL_SERVER_ERROR` | Unexpected infrastructure failure; no internal details exposed |

All error responses contain `code`, `message`, and `details` as required by
DOC-05 §1.8. Unrelated application validation retains its existing error mapping.

Rejected methods advertise `Allow: GET, HEAD, OPTIONS` for the collection and
`Allow: GET, HEAD, PATCH, OPTIONS` for a detail resource, on both prefixes. The
container discovers these methods from MVC mappings before rendering a TRACE
error. TRACE stays disabled and never reflects request headers or bodies. CORS
method policy remains separate from resource method discovery.

Console output and the optional `logging.file.name`/`logging.file.path` rolling
file use the sanitizers in `logback-spring.xml`. Parser and JDBC diagnostics omit
request/vendor text; throwable output retains types and code locations but drops
messages from the complete cause/suppressed-exception graph. Logs retain time,
level, logger, thread, and available SQLSTATE. Unsupported media types are handled
with a fixed response before Spring's default resolver can log their parameters.
Custom appenders must use `%safeMessage` and `%safeThrowable`; raw `%msg`, `%ex`,
MDC, structured arguments, or replacement logging configurations bypass this
boundary. Application logs must not deliberately include profiles or credentials.

## Canonical migration history after current-main integration

Integrated main `f8f0b70a8e4e9222bd2c9d2063baf1687dfabc87`, which contains
V1–V14 (LMS-6 V14) and V17 (LMS-9). These merged files and checksums remain unchanged.
The unapplied LMS-13 SQL is reallocated by filename only, without changing SQL bytes:

| Previous unmerged LMS-13 version | Current migration | Flyway checksum |
| --- | --- | ---: |
| V14 (earlier feature V7) | `V18__index_lms_user_access_default_sort.sql` | 354517154 |
| V15 (earlier feature V8) | `V19__add_identity_sync_watermarks.sql` | 1240703539 |
| V16 (earlier feature V9) | `V20__create_audit_logs.sql` | 67085040 |

The combined production set is V1–V14,V17–V20: 18 migrations. Missing V15/V16 versions
are intentional. Versions are compared with current main only; unmerged PRs do not reserve versions.

Before reallocation, the stopped local persistent PostgreSQL volume was mounted read-only and copied
into an isolated inspection volume. SELECT-only inspection of every LMS Flyway history in that copy
found no applications of the old LMS-13 V14/V15/V16 scripts. The actual development database was at
V1–V13; older LMS-9 test histories included V17. The original volume/container was not started or
modified. Old LMS-13 versions seen in prior review databases belong to disposable test runs.
External/shared/production histories are not accessible and are not claimed verified.

Inspect each deployment database before starting this version:

```sql
SELECT installed_rank, version, script, checksum, success
FROM flyway_schema_history
ORDER BY installed_rank;
```

| Existing history | Upgrade procedure |
| --- | --- |
| Empty database | Apply V1–V14,V17–V20 and validate. |
| Current main V1–V14,V17 | Apply exactly V18–V20; preserve existing data and checksums. |
| Canonical V13/V4 | Apply remaining current-main migrations, then V18–V20; preserve earlier history. |
| Applied old feature V7/V8/V9 or LMS-13 V14/V15/V16 | Stop. Source renames cannot reconcile a persistent conflicting history. Inventory/back up it and verify a separately approved reconciliation runbook on a clone. |
| V17 already applied without main's V14 | Stop and coordinate the target's history before deployment; do not enable out-of-order migration blindly. |

Do not repair/reset, rewrite/renumber applied history, disable validation or change merged V14/V17.
No shared or production database is migrated by this implementation task.

`MigrationPolicyTest` pins all 18 filenames/checksums and the exact unique version set. The existing
`MainV13MigrationIntegrationTest` now exercises both V13 and current-main V17 baselines. Its V17 case
preserves populated Assignment, attachment, submission, learning-signal and mastery rows, original
constraints, user-access fields and existing migration history while applying exactly three migrations.
Both cases verify watermark precision, index definition, audit actor FK/restricted deletion and no-op
restart. V4, bootstrap, index and watermark upgrade regressions remain covered. Run a clean build after
renaming so obsolete V14/V15/V16 feature resources cannot remain in target/classes.

## Administrative error isolation

`AdminUserExceptionHandler` is controller-scoped and retains administrative validation, missing-user,
last-Administrator, persistence and framework error contracts. GlobalExceptionHandler otherwise retains
current-main behavior, including INVALID_QUERY_PARAMETER for malformed recommendation/mastery UUIDs.
Only method/media failures raised before a controller is selected use a narrow admin-users route check
(including context paths and both route aliases) to delegate to the same administrative formatter.
No adaptive routes are added to a global classification list. Existing tests keep admin probes under
scoped advice and non-admin probes under main's global advice; no authorization/audit tests are removed.

`Lms13ErrorContractPostgresHttpTest` exercises actual signed-JWT HTTP requests for malformed Student
recommendation and Tutor mastery IDs, both admin route aliases, invalid admin bodies, and unauthorized,
wrong-role, unprovisioned and suspended access. Existing LMS-6/LMS-7/LMS-9 implementations are preserved.

## Historical verification before current-main integration

Use JDK 25 and a **disposable PostgreSQL 18.6 database**. Set `DB_URL`, `DB_USERNAME`,
and `DB_PASSWORD`; the test user needs schema-creation permission. Tests apply
Flyway migrations and use isolated schemas, including concurrency and failure
injection. Signed JWT/JWKS fixtures run locally; Central Identity is not required.

```powershell
.\mvnw.cmd clean verify
```

The merge was verified on 2026-11-09 at source commit
`9b37488d35bbe8b6ad878a5f414a7d738e895d2f`, using JDK 25.0.5.0.2+1,
Maven 3.9.12, PostgreSQL 18.6 (`180006`), and ffprobe 9.0.1:

| Gate | Result |
| --- | --- |
| Focused migration/advice/controller/logging tests | 296 passed |
| Affected PostgreSQL/HTTP/security integration tests | 391 passed |
| Added V13/index/mixed-advice regression gate | 71 passed |
| `mvn --batch-mode --no-transfer-progress clean verify` | 1,587 tests in 75 suites; zero failures, errors or skips; BUILD SUCCESS in 2m26s; executable JAR packaged |

The gates overlap; the full-suite count is the final total. Verification used a
fresh isolated build copy because the original checkout contained an unrelated
locked `target` artifact. All 321 source/POM/resource inputs were compared
byte-for-byte with the tested copy. The database was a new disposable instance;
its final history contained 16 successful versioned migrations, ending at V16.
Source SQL provenance and Flyway checksum checks passed. Database logs contained
both intentional constraint-failure markers (`reject_test_role`,
`reject_test_profile`) and none of the forbidden profile canaries or failing-row
details. Existing feature databases still require the reconciliation decision
above before deployment.

`AdminUserHttpRegressionTest` uses a real servlet server and request-owned
transactions. It verifies DOC-05 round trips, charset/media rejection before
commit, read-only projections, item authorization, exact domain codes,
pagination boundaries, audit content and database-induced audit rollback.
It also verifies exact 405 method advertisements, TRACE non-reflection, and the
absence of synthetic identity/token markers in rendered console and file output
after malformed targets, media-type errors, and row-constraint failures. Failed
updates preserve the complete user record and create no success audit; recovery
is verified after removing the test constraint.
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

## Current-main integration verification (2026-11-15)

Main baseline: `f8f0b70a8e4e9222bd2c9d2063baf1687dfabc87`.
Integration merge: `3affe5fdd140d17fad64f50057acdc71d280d9e5`.
The corrections preserve the existing administrator service/controller, JWT identity synchronization,
role/status validation, last-active-Administrator lock and transactional audit implementation.

Executed on JDK 25.0.5.0.3 and disposable PostgreSQL 18.6, with explicit database variables:

```bash
DB_URL=jdbc:postgresql://localhost:55436/lms13_fixes_focus DB_USERNAME=postgres DB_PASSWORD='' \
/workspace/.setup/mvn -B -Dtest=Lms13ErrorContractPostgresHttpTest,AdminUserControllerTest,AdminUserControllerIntegrationTest,AdminUserHttpRegressionTest,Lms9AdaptivePostgresHttpTest,GlobalExceptionHandlerTest,MigrationPolicyTest,MainV13MigrationIntegrationTest,AssignmentMigrationPostgresTest,MasteryMigrationPostgresTest test

DB_URL=jdbc:postgresql://localhost:55436/lms13_fixes_focus DB_USERNAME=postgres DB_PASSWORD='' \
/workspace/.setup/mvn -B --no-transfer-progress -Dtest=ClassAdminIntegrationHttpTest,Lms13ErrorContractPostgresHttpTest test

DB_URL=jdbc:postgresql://localhost:55436/lms13_fixes_verify_final DB_USERNAME=postgres DB_PASSWORD='' \
/workspace/.setup/mvn -B --no-transfer-progress clean verify
```

`/workspace/.setup/mvn` delegates to this repository's Maven Wrapper with the installed JDK 25,
trusted Maven settings and dependency cache. Each database was created explicitly in an isolated test
container; no test used the stopped persistent development database or a production/shared database.

| Gate | Result |
| --- | --- |
| Focused migration/admin/LMS-9/error contracts | 463 passed, zero failures/errors/skips |
| Class/admin header and HTTP contract gate | 12 passed, zero failures/errors/skips |
| Final clean verify | 1,878 tests in 89 suites, zero failures/errors/skips; BUILD SUCCESS; application JAR packaged |
| Existing admin HTTP / controller integration / controller tests | 193 / 54 / 95 passed |
| Existing admin service / PostgreSQL integration tests | 58 / 12 passed |
| JWT converter / validation | 47 / 69 passed |
| New cross-module HTTP contracts | 4 passed |
| Main V13/current-main V17 upgrade paths | 2 passed |
| LMS-6 Assignment HTTP / file storage | 29 / 12 passed |
| LMS-9 HTTP | 40 passed |
| Quiz / Assignment evidence readers | 31 / 41 passed |
| Calculator / persistence | 43 / 17 passed |
| Recommendation PostgreSQL / ranker | 35 / 15 passed |
| LMS-7 progress suites | 11 passed |

Counts overlap; the full-suite total is 1,878. The final full execution completed at 03:30:25 UTC.
These are newly executed results for this integration, not historical CI reports. The suite contains
both unit/mock tests and real PostgreSQL/HTTP tests, plus the existing H2 bootstrap tests.

Earlier integration runs exposed old migration-count/version expectations, standalone tests wired only
to global advice, and admin formatting expectations on non-admin probes. Tests now exercise the proper
scoped advice and exact current-main non-admin contracts; no security/audit assertions were removed.
The first full run had two failures because a Class test expected the admin-normalized Allow header.
Its non-admin assertion now preserves main's `GET, PATCH`; admin users retain `GET, HEAD, PATCH, OPTIONS`.
The final full run above passes. No production LMS-6/LMS-9 implementation was changed.

External deployment histories remain unverified. Inspect them before deploying; stop if older feature
migration versions were applied persistently. No Flyway repair/reset, validation bypass, shared-history
edit or production reconciliation was performed.
