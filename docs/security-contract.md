# LMS-2 security and bootstrap contract

Approved implementation specification for the LMS-2 security architecture, dated 2026-10-30.
This contract records the task's decisions against DOC-01 (bootstrap), DOC-04
section 2.1.5 (local identity projection), DOC-05 (authentication and HTTP errors),
and the LMS-2 research report. It defines required behavior, architectural guarantees,
and implementation components.

## JWT identity and security

- Central Identity is the trusted identity provider (IdP). It issues signed
  **RS256 Bearer access tokens**. The LMS verifies signatures using public keys
  from its configured, trusted JWKS endpoint and validates the configured issuer.
- Only RS256 is accepted. Unsigned tokens (`alg: none`), HMAC algorithms, and all
  other signing algorithms must be rejected fail-closed.
- Required claims are `sub`, `exp`, and `iat`:
  - `sub` is the stable Central Identity `user_id`, in canonical RFC 4122 UUID
    text form, matching exactly
    `^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`.
    Uppercase and lowercase hexadecimal are accepted. Blank, missing,
    abbreviated, incorrectly hyphenated, or whitespace-padded values are invalid.
  - `exp` is a mandatory, valid NumericDate expiration timestamp. Tokens without
    expiration are invalid, even if their signature verifies.
  - `iat` is a mandatory, valid NumericDate issuance timestamp.
- Validate raw `exp`, `iat`, and optional `nbf` before timestamp conversion:
  values must be finite JSON numbers in the range `0 <= seconds < 253402300800`
  (1970 through 9999). Check bounds using exact decimal values before any
  floating-point coercion, so tiny negative values and high-precision fractions
  retain their sign and range. This prevents numeric coercion and millisecond overflow.
- Optional `scope` and `scp` must be strings or arrays containing only strings.
  Optional `cnf` must be an object; its `x5t#S256` member, when present, must be
  a string. Explicit nulls and malformed shapes are invalid tokens, producing
  the same JSON 401 response and Bearer challenge at the original request URI.
- Retain standard timestamp validation, including `nbf` when supplied. Clock
  skew tolerance must not exceed **60 seconds**.
  Compare the original decimal timestamps against the injected clock before
  converting them to nanosecond-resolution `Instant` claims. Accept `exp` at
  exactly `now - 60s` and `nbf` at exactly `now + 60s`, rejecting values beyond
  those limits without rounding. Also require `iat <= now + 60s`, using the
  same exact decimal comparison. Require `exp > iat`; valid sub-second
  lifespans must survive token construction without whole-second truncation.
- Missing or malformed tokens, invalid signatures, unsupported algorithms,
  invalid issuer, expired tokens, and missing or malformed required claims
  result in **HTTP 401 Unauthorized** with the JSON format below.
- Issuer and JWKS addresses come from trusted application configuration, never
  from an unverified token. Audience requirements need a separate agreed IdP
  contract; this implementation does not invent a required audience.

## Local access and RBAC

- A valid token establishes identity only. External role, authority, and scope
  claims MUST NOT grant LMS authorization.
- The local `lms_user_access` table is the sole source of truth for LMS roles:
  `STUDENT`, `TUTOR`, and `ADMINISTRATOR`, exposed to Spring Security as
  `ROLE_STUDENT`, `ROLE_TUTOR`, and `ROLE_ADMINISTRATOR` respectively.
- The identified local user must exist and have `access_status = ACTIVE`.
  A missing local user or `SUSPENDED` local access results in **HTTP 403
  Forbidden**, using the JSON format below. A valid token does not auto-create
  local access or grant a default role.
- Local status and role must be checked for each request; reuse of the same
  token must not preserve revoked access. Resource ownership and class
  membership checks remain additional requirements.
- Infrastructure failures during local-access lookup fail closed and return
  **HTTP 500 Internal Server Error**, without a Bearer challenge. Internal
  servlet ERROR dispatches may render the safe error response; ordinary requests
  to `/error` still require an active local role. Internal errors always use
  `Content-Type: application/json;charset=UTF-8`, regardless of the `Accept` header.
  The server-error JSON follows DOC-05 §1.8 with `code`, `message`, and `details`.
  `message` is a fixed safe explanation, never an exception message, stack trace, or SQL.
  Recovery requires a fresh successful local-access lookup with no authority fallback.
- `email` and `full_name` are display copies of Central Identity information.
  The identity key is `user_id`, never email (DOC-04 section 2.1.5).
  Local email is **not unique** in the LMS schema; Central Identity's email
  uniqueness applies to its separate identity database.
  Upgrades from V1 must preserve duplicate email projections through V2 and V3.
- After trusted JWT validation, optional `fullName` and `email` claims synchronize
  the existing row selected by `sub`. Missing claims preserve existing copies;
  supplied claims must satisfy the local display-field validation or return 401.
  Updates never provision users or change UUID, role, status, or creation time.
  Suspended users can refresh display copies but still receive 403 and do not
  record a successful LMS login. A column-specific update and a fresh access read
  preserve concurrent role/status revocations.
- For active local users, the first authentication with a token issued after the
  prior local login records the LMS clock time in `last_login_at`. Reusing that
  token does not advance it on every API request. This is local authentication,
  independent of whether the particular endpoint permits the user's role, and
  distinct from Central Identity's authentication timestamp.

## CORS and OPTIONS

- `lms.allowed-origins`, backed by `LMS_ALLOWED_ORIGINS`, defines exact allowed
  browser origins. Defaults are `http://localhost:3000,http://localhost:5173`.
  An override replaces the defaults; comma-separated entries are trimmed and
  deduplicated. For example, `LMS_ALLOWED_ORIGINS=https://portal.example.edu`.
- Startup rejects blank entries, wildcards, literal `null`, non-HTTP(S) schemes,
  missing hosts, invalid ports, userinfo, paths (including a trailing slash),
  queries, and fragments. Allowed methods and headers remain explicitly listed;
  credentials are enabled and preflight max age is 3600 seconds.
- SecurityConfig installs exactly one JsonCorsFilter before Bearer authentication,
  explicitly disabling the default CORS configurer to avoid duplicate processing.
  Allowed-origin actual requests retain CORS headers on authentication,
  authorization, and server errors; they still require valid local authorization.
- Allowed preflights and ordinary OPTIONS requests return HTTP 200 before Bearer
  authentication or local-access lookup, including during a database outage.
- CORS denials, including invalid origins and rejected preflight methods or
  headers, return the DOC-05 §1.8 JSON 403 response. OPTIONS does not
  bypass the CORS whitelist.

## Administrator bootstrap

- The initial administrator is created exclusively through a trusted **non-web
  CLI / CommandLineRunner** execution, disabled during ordinary web startup.
  The operator identifies an existing active Central Identity account by UUID.
  See [administrator bootstrap operations](administrator-bootstrap.md).
- There must be **no REST endpoint** for administrator bootstrapping.
- A dedicated singleton `lms_setup_state` row is created ahead of bootstrap.
  Bootstrap acquires a database-level pessimistic lock using
  `SELECT ... FOR UPDATE` on that row. A missing row must fail closed.
- Checking setup state, checking existing administrators, creating or
  activating the initial local administrator, and marking setup complete occur
  within the same transaction. Concurrent processes serialize on the same row;
  an in-memory lock or a count of administrators alone is insufficient.
- Setup is strictly run-once: any existing Administrator (ACTIVE or SUSPENDED)
  blocks bootstrap, as required by DOC-04 section 2.1.5. Existing installations
  are sealed with a selected administrator's UUID before the nontransactional
  runner reports refusal. Later suspension, demotion, or deletion does not reopen
  setup. Same-UUID retries also refuse without changing closure metadata.
- `bootstrapped_by` records the initialized account or, during reconciliation,
  an existing administrator ordered by `created_at` then `user_id`. It is not an
  authenticated operator identity or proof of the historically first administrator.
  Deletion nulls only this FK; completion and its timestamp persist.
- GLOBAL protects participating initialization transactions. Future provisioning
  paths must lock GLOBAL before affected user rows; arbitrary external user-table
  writes are outside this guarantee and must be quiescent during initial setup.
- Failed initialization rolls back both the user change and completion marker.
  Successful initialization persists the completion marker across restarts.

## Standard security error response (DOC-05 alignment)

All HTTP error responses follow DOC-05 §1.8 `{code, message, details}`.
Authentication, authorization, CORS, and servlet errors use
`Content-Type: application/json;charset=UTF-8` and this payload:

```json
{
  "code": "UNAUTHORIZED",
  "message": "A valid Bearer access token is required.",
  "details": []
}
```

`code` is a machine-readable string: `UNAUTHORIZED` for 401, `FORBIDDEN` for
403, `NOT_FOUND` for 404, and `INTERNAL_SERVER_ERROR` for 500. Other recognized
HTTP statuses use their uppercase status name, such as `BAD_REQUEST` for 400.
Missing or unrecognized servlet error statuses fall back to HTTP 500 and
`INTERNAL_SERVER_ERROR`. The HTTP status remains in the response status line.
`message` is a human-readable, safe explanation. `details` is an optional array
under DOC-05 §1.8; these handlers always emit an empty array (`[]`). No additional
payload fields are included. Do not expose tokens, keys, raw exception details,
stack traces, or database information. Authentication errors retain
`WWW-Authenticate: Bearer`.

## Implementation and component structure

The LMS-2 security implementation has the following components. Unit/native-H2
coverage and PostgreSQL/servlet integration coverage are separate verification gates:

1. **Central Identity JWT Validation & Claim Contract (`6e1dbc5`):**
   - Implements RS256 token verification against Central Identity JWKS.
   - Enforces strict claim validation: RFC 4122 UUID `sub`, finite numeric timestamps (`exp`, `iat`, `nbf`), and clock skew tolerance of at most 60 seconds.
   - Standardizes JSON 401 Unauthorized entry point with `WWW-Authenticate` Bearer challenge and DOC-05 §1.8 error payload.
   - Validates malformed claim rejection (missing claims, wrong formats, invalid algorithms) fail-closed.

2. **Local Access Control & RBAC Enforcement (`9853cb7`, `4f1fc70`):**
   - Implements local database role mapping from `lms_user_access` (`ROLE_STUDENT`, `ROLE_TUTOR`, `ROLE_ADMINISTRATOR`).
   - Restricts authorization strictly to accounts with `access_status = ACTIVE`; missing or `SUSPENDED` accounts receive standard JSON 403 Forbidden.
   - Ensures ordinary requests require an active local LMS role (checking only `authenticated()` is insufficient).
   - Handles database lookup outages fail-closed with HTTP 500 JSON envelope at the original request path, preventing stack trace or sensitive info leaks.

3. **Run-Once Administrator Bootstrap & Database Locking (`b5724ae`, `0e061f1`):**
   - Provides trusted non-web CLI `AdminBootstrapRunner` (`--spring.main.web-application-type=none`), strictly detached from and disabled during web HTTP startup.
   - Enforces database-level pessimistic lock (`SELECT ... FOR UPDATE` via `PESSIMISTIC_WRITE`) on singleton table `lms_setup_state` to serialize concurrent bootstrap attempts across multi-instance deployments.
   - Enforces permanent run-once guarantee: records setup completion in database migration with a selected UUID if any administrator already exists (ACTIVE or SUSPENDED); subsequent suspension, demotion, or deletion cannot reopen bootstrap.
   - Completely rolls back both user updates and setup completion markers upon any initialization failure.

4. **Flyway Database Migration (`V3__create_lms_setup_state.sql`):**
   - Creates and seeds the singleton `lms_setup_state`, recording an existing administrator's UUID on closure.
   - V1 permits duplicate local emails; V2 widens columns without adding uniqueness; V3 adds setup state. UUID remains the identity key, consistent with DOC-04 section 2.1.5.
   - Native H2 tests execute V3 from a final-V2-equivalent fixture. PostgreSQL tests exercise actual migration history and transaction locking; H2 results do not replace those checks.
   - Rewriting feature-branch V3 assumes it has not been applied to retained deployments; an applied checksum requires an explicit forward-migration decision, never automatic Flyway repair.
