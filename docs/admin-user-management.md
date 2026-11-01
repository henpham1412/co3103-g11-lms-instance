# LMS-13: UC7.1 Local User Administration

This guide documents the endpoints implemented by LMS-13 commits 1–4 and their
verification collection. They manage `LmsUserAccess` in the current LMS database;
they do not change Central Identity accounts or access to other LMS instances.

The implemented routes are `GET /api/admin/users` and
`PATCH /api/admin/users/{userId}`. They use camelCase JSON and the query names
below. DOC-05's general `/api/v1` prefix and §5.1 snake_case examples are not the
wire contract of this implementation. A single-user GET route and AuditLog writes
are not implemented by these commits. The error envelope follows DOC-05 §1.8.

## Authentication and local authorization

Both endpoints require `Authorization: Bearer <access_token>` and an **ACTIVE local
ADMINISTRATOR**, mapped to `ROLE_ADMINISTRATOR`. Central Identity JWTs establish
identity through their UUID `sub`; token role claims cannot grant local authority.
The local role and status are loaded on every request. Demotion or suspension
therefore takes effect on the next request even when the same token is reused.

Send `Accept: application/json`, and `Content-Type: application/json` for PATCH.
PATCH rejects response formats that do not accept JSON with `406 NOT_ACCEPTABLE`
before invoking the mutation service. This includes `application/xml`, `text/html`,
and `application/json;q=0`, even if a less specific wildcard has a positive quality.
See the [security contract](security-contract.md) for signature, issuer, claim,
CORS, and local-access validation, and [administrator bootstrap](administrator-bootstrap.md)
for provisioning the initial administrator.

## GET /api/admin/users

Returns `200 OK` with a page of local access records. Filters are optional and
combined with AND; name, email, and UUID matches within `q` are combined with OR.

| Query parameter | Values and behavior | Default |
| --- | --- | --- |
| `q` | Trimmed, case-insensitive substring of local `fullName` or `email`; a canonical UUID also matches `userId` exactly. `%`, `_`, and `\` are literal search characters. A blank query adds no filter. | No search filter |
| `role` | `STUDENT`, `TUTOR`, `ADMINISTRATOR` (case-sensitive) | All roles |
| `status` | `ACTIVE`, `SUSPENDED` (case-sensitive) | Both statuses |
| `page` | Zero-based page number | `0` |
| `size` | Positive page size; resolved by Spring Data's pageable argument resolver | `20` |
| `sort` | Entity property and direction, e.g. `createdAt,desc` or `fullName,asc`; repeat for multiple properties | `createdAt,desc` |

Use nonnegative page numbers and positive sizes. Pagination coercion and limits
come from Spring Data; a resolved `page * size` greater than `2147483647` is rejected
with `400 BAD_REQUEST` before querying users. Sort names are allowlisted
entity properties: `userId`, `fullName`, `email`, `lmsRole`, `accessStatus`,
`lastLoginAt`, `createdAt`, `updatedAt`. In particular, the response field `role`
maps to sort property `lmsRole`. Unknown or unlisted sort properties (including
`role` and `id`) produce `400 BAD_REQUEST`. Add `sort=userId,asc` to break timestamp
ties when stable ordering matters.

```http
GET /api/admin/users?q=alice&role=STUDENT&status=ACTIVE&page=0&size=5&sort=createdAt,desc
Authorization: Bearer <admin_jwt>
Accept: application/json
```

```json
{
  "content": [
    {
      "userId": "22222222-2222-4222-8222-222222222222",
      "role": "STUDENT",
      "accessStatus": "ACTIVE",
      "createdAt": "2026-01-01T00:00:00Z",
      "updatedAt": "2026-01-01T00:00:00Z"
    }
  ],
  "page": 0,
  "size": 5,
  "totalElements": 1,
  "totalPages": 1,
  "last": true
}
```

`content` is an array of the user schema below. `page`, `size`, `totalElements`,
and `totalPages` are integers; `totalElements` counts records matching all filters,
and `last` is boolean. No matches produce `200` with empty `content`,
`totalElements: 0`, `totalPages: 0`, and `last: true`. An out-of-range page also
has empty content, retaining the totals for the matching records. The response
does not include name or email, even though those local display copies are searchable.

## PATCH /api/admin/users/{userId}

`userId` is the existing user's immutable Central Identity UUID. This endpoint
updates local access only; it does not create a missing user.

| Body property | JSON type | Allowed values | If omitted or null |
| --- | --- | --- | --- |
| `role` | String or null | `STUDENT`, `TUTOR`, `ADMINISTRATOR` | Preserve existing role |
| `accessStatus` | String or null | `ACTIVE`, `SUSPENDED` | Preserve existing status |

Either or both fields may be supplied. The request DTO is `UpdateUserAccessRequest`;
the separate `@NotNull` constraints on `UpdateUserRoleRequest` and
`UpdateUserStatusRequest` are not used by this route. The GET filter is called
`status`, but the PATCH body field is **`accessStatus`**.
Only the exact named strings above or JSON `null` are accepted. Numeric ordinals,
numeric strings (for example, `2` or `"2"` for `role`), blank strings, and other JSON
types produce `400 BAD_REQUEST` before any access change.

```json
{ "role": "TUTOR" }
```

```json
{ "accessStatus": "SUSPENDED" }
```

```json
{ "role": "TUTOR", "accessStatus": "ACTIVE" }
```

Success is `200 OK` with one user object:

| Response field | Schema |
| --- | --- |
| `userId` | UUID string |
| `role` | `STUDENT`, `TUTOR`, or `ADMINISTRATOR` |
| `accessStatus` | `ACTIVE` or `SUSPENDED` |
| `createdAt` | ISO-8601 instant; preserved by an access update |
| `updatedAt` | ISO-8601 instant; captured after the persistence flush |

An actual change refreshes `updatedAt`. A no-op need not change it. `{}` and
`{"role": null, "accessStatus": null}` are accepted no-ops with `200`, not validation
errors. A missing/zero-length body, whole-body JSON `null`, malformed JSON, or
invalid enum values produce `400 BAD_REQUEST`. Collection case 2.6 uses those
invalid payloads; case 2.8 explicitly verifies the accepted empty-object/null-field
semantics. Do not expect a 400 for null fields on this endpoint.

## Error envelopes (DOC-05 §1.8)

Errors use JSON with a stable machine-readable `code`, a safe human-readable
`message`, and `details`. DOC-05 permits omitting `details`; these handlers always
include it. HTTP status is carried by the status line, not an extra body field.

```json
{
  "code": "LAST_ADMIN_PROTECTION",
  "message": "Cannot demote or suspend the last active administrator",
  "details": []
}
```

| HTTP status | Code | Trigger / message |
| --- | --- | --- |
| `400` | `BAD_REQUEST` | Malformed body, invalid role/status enum (including numeric tokens/strings), invalid UUID path or enum query parameter, unsupported sort property, or overflowing page offset: `Bad Request` |
| `400` | `LAST_ADMIN_PROTECTION` | Final active administrator would be demoted or suspended: `Cannot demote or suspend the last active administrator` |
| `401` | `UNAUTHORIZED` | Missing credentials: `A valid Bearer access token is required.` Invalid/expired token: `The Bearer access token is invalid or expired.` |
| `403` | `FORBIDDEN` | Active STUDENT/TUTOR, suspended caller, or identity without local access: `Access to this resource is denied.` |
| `404` | `USER_NOT_FOUND` | Well-formed target UUID with no local record: `User not found` |
| `406` | `NOT_ACCEPTABLE` | PATCH response format excludes JSON; rejected before mutation: `Not Acceptable` |
| `500` | `INTERNAL_SERVER_ERROR` | Infrastructure or unexpected internal failure; safe error response, no internal exception details |

These cases have `details: []`. The shared bean-validation handler can instead
return `message: "Validation failed"` with `details` entries of the form
`{"field": "role", "message": "Role is required"}` when a constrained DTO fails
validation; the partial-update DTO above has no required fields. Authentication
errors include `WWW-Authenticate: Bearer`; authorization errors have no Bearer
challenge. Security/servlet errors use `application/json;charset=UTF-8`, and
controller errors use `application/json`. Both are JSON-compatible.

## Last-Active-Admin Protection and concurrency

An active administrator has **both** `lmsRole = ADMINISTRATOR` and
`accessStatus = ACTIVE`. A request removes an active administrator if the target
currently has both values and the request supplies a non-administrator role or
`accessStatus = SUSPENDED`. If the active-administrator count is at most one,
the service rejects the update with `400 LAST_ADMIN_PROTECTION` before any change.
Suspended administrators do not count as available administrators.

The rule applies equally to self-updates and updates by another administrator.
Self-demotion or self-suspension is allowed when another active administrator
remains. Promotions, reactivations, and no-ops do not remove an active
administrator. Role and status changes in one request are checked together.

`AdminUserService.updateUserAccess` runs in a single `@Transactional` transaction:

1. Acquire `LmsSetupStateRepository.lockGlobalState()` on the singleton
   `LmsSetupState` / `lms_setup_state` row whose ID is `GLOBAL`, using
   `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`). Flyway V3 creates this row.
2. Load the target only after taking that lock, then count active administrators
   when the proposed change removes one. A missing target produces `USER_NOT_FOUND`.
3. Apply allowed fields and `saveAndFlush` so persistence callbacks update the
   timestamp before the response is built. Commit releases the lock; failure
   rolls the transaction back, including any flushed changes.

The database lock serializes all participating access updates and bootstrap
transactions across application instances. For two concurrent removals of two
active administrators, the second updater waits, observes the first committed
change, and refuses to remove the remaining administrator. The order is always
GLOBAL before target reads/locks and the count. A JVM-local mutex or an unlocked
count would not provide this guarantee.

Updates do not rewrite setup completion metadata or reopen bootstrap. A missing
GLOBAL row fails closed with an internal error; it is not recreated by PATCH.
Future writers must use the same lock order. Direct SQL or other writers that
bypass GLOBAL are outside this protection. See the bootstrap guide for setup
closure and recovery boundaries.

## Verify with Postman

Import [LMS-13_AdminUserManagement.postman_collection.json](postman/LMS-13_AdminUserManagement.postman_collection.json).
It uses the [Postman Collection v2.1.0 schema](https://schema.getpostman.com/json/collection/v2.1.0/collection.json)
and contains executable response assertions for every request.

| Collection variable | Value to set locally |
| --- | --- |
| `baseUrl` | Defaults to `http://localhost:8080` as requested. The LMS application defaults to **8081**: use `http://localhost:8081`, or start the LMS with `SERVER_PORT=8080` on a free port. Point to the LMS, not Central Identity. |
| `admin_jwt` | Unexpired Central Identity access token for an ACTIVE local ADMINISTRATOR, without the `Bearer ` prefix |
| `student_jwt` | Token for a separate ACTIVE local STUDENT, without the `Bearer ` prefix |
| `target_user_id` | UUID of a disposable ACTIVE local STUDENT/TUTOR, distinct from both token subjects |

Use a disposable verification database with the migrations applied, the GLOBAL
row present, and exactly one ACTIVE administrator for case 2.5. Provision token
subjects by their verified Central Identity UUIDs using the local development
fixture procedure; these endpoints cannot create users. The administrator can be
initialized using the linked bootstrap CLI. Include fixtures with all three roles
and both statuses to make the filter cases meaningful. For keyword search, use a
known name/email; the supplied example is `example.test`. The UUID-search case
uses `target_user_id` and asserts exactly that record is returned. Keep real tokens
in local Postman variable values and out of exported collections.

Run the collection in order using Collection Runner (one iteration). Case 2.1
changes the target to TUTOR, 2.2 suspends it, and 2.3 reactivates it. The target
ends as ACTIVE/TUTOR; it is not automatically restored to its original role.
The separate student-token account must remain ACTIVE/STUDENT for both 403 cases.
For 2.4, ensure the reserved example UUID
`ffffffff-ffff-4fff-8fff-ffffffffffff` does not exist in the fixture database.

| Case | Verification |
| --- | --- |
| 1.1 | Default page 0, size 20, user schema, descending creation time |
| 1.2a–b | Keyword and exact-UUID searches |
| 1.3a–c | STUDENT, TUTOR, ADMINISTRATOR filters |
| 1.4a–b | ACTIVE and SUSPENDED filters |
| 1.5 | `page=0&size=5&sort=createdAt,desc`, page metadata and ordering |
| 1.6–1.7 | Explicitly unauthenticated 401 and student-token 403 |
| 2.1–2.3 | TUTOR assignment, suspension, reactivation: 200 and returned values |
| 2.4 | Missing user: 404 and `USER_NOT_FOUND` |
| 2.5a–b | Last active administrator demotion and suspension: 400 and `LAST_ADMIN_PROTECTION` |
| 2.6a–e | Missing body, JSON null, invalid role/status, malformed JSON: 400 and `BAD_REQUEST` |
| 2.7 | Student PATCH: 403 and `FORBIDDEN` |
| 2.8a–b | Empty object and null fields: 200, retaining ACTIVE/TUTOR after 2.1–2.3 |

Before each 2.5 request, a folder script lists ACTIVE administrators using the
admin token. It requires exactly one and derives a run-local `last_active_admin_id`
for the PATCH URL, leaving `target_user_id` unchanged. A failed prerequisite is a
failed test and skips the PATCH using `pm.execution.skipRequest()`; it is **not a
passing protection test**. Use a current Postman runtime supporting that API.
Run with other administrative writers idle: the preflight read is not atomic with
PATCH and does not replace the server-side concurrency test.

Each error assertion checks status, exact `code`/`message`, the three-field envelope,
empty `details`, and the appropriate authentication challenge. Successful lists
check pagination and user fields, and filters check every returned row. A filter
with no matching fixtures can legitimately return an empty list, so seed matching
records when checking filter coverage. Successful PATCH responses are checked for
the target UUID and expected values; run 2.1–2.3 before the later no-op assertions.

## Verify with MockMvc and PostgreSQL integration tests

Use **JDK 25** and a disposable PostgreSQL database accessible to the configured
database user. Set `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` for that database;
the user must be able to create the schemas used by the tests. The full suite
includes real Flyway migrations, schema resets, rollback tests, and concurrent
transactions, so use a fresh test database rather than an application database.
The CI configuration uses PostgreSQL 18. JWT integration tests serve their own
local JWKS and sign test tokens; a running Central Identity service is not needed.

From the repository root in PowerShell:

```powershell
# Set JAVA_HOME to your installed JDK 25 directory and configure the test DB first.
./mvnw.cmd --version
./mvnw.cmd test-compile
if ($LASTEXITCODE -ne 0) { throw 'test-compile failed' }
./mvnw.cmd test
if ($LASTEXITCODE -ne 0) { throw 'test failed' }
```

Maven must report Java 25 and both commands must finish with `BUILD SUCCESS` and
zero failures/errors/skips. Review `target/surefire-reports/` for per-suite evidence.
For a focused rerun after the full checks:

```powershell
./mvnw.cmd '-Dtest=AdminUserRequestValidationTest,LmsUserAccessSpecificationTest,AdminUserServiceTest,AdminUserServiceIntegrationTest,AdminUserControllerTest,AdminUserControllerIntegrationTest' test
```

| Test suite | Evidence |
| --- | --- |
| `AdminUserControllerTest` | Standalone MockMvc request binding, pagination/sort mapping, partial/no-op updates, malformed requests, exact error envelopes; mocked service, not an RBAC integration test |
| `AdminUserControllerIntegrationTest` | MockMvc with the real security chain, signed JWTs/JWKS, database, DTO/service/repository path; filters, updates, missing/invalid tokens, role restrictions, suspended/missing local access, last-admin errors, and immediate revocation with the same JWT |
| `AdminUserServiceTest` | Business-rule branches and GLOBAL-before-target/count interaction order |
| `AdminUserServiceIntegrationTest` | PostgreSQL persistence, timestamps, setup metadata preservation, rollback after flush, suspended-admin exclusion, and concurrent demotion/suspension with exactly one update succeeding |
| `LmsUserAccessSpecificationTest` | Specification predicates for filter combinations, name/email/UUID search, and literal wildcard handling; database execution is covered by the service integration tests |
| `AdminUserRequestValidationTest` | Constraints on the separate role/status DTOs; these do not make partial-update fields mandatory |

A sequential Postman run verifies the HTTP contract. The concurrent service tests
verify the database locking guarantee; H2-only checks do not establish PostgreSQL
locking behavior.
