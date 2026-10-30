# LMS-2 security and bootstrap contract

Approved implementation decisions for Commit 0 and Commit 1, dated 2026-10-30.
This contract records the task's decisions against DOC-01 (bootstrap), DOC-04
section 2.1.5 (local identity projection), DOC-05 (authentication and HTTP errors),
and the LMS-2 research report. It defines required behavior, not a declaration
that all LMS-2 features are already implemented.

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
- Retain standard timestamp validation, including `nbf` when supplied. Clock
  skew tolerance must not exceed **60 seconds**.
- Missing or malformed tokens, invalid signatures, unsupported algorithms,
  invalid issuer, expired tokens, and missing or malformed required claims
  result in **HTTP 401 Unauthorized** with the JSON format below.
- Issuer and JWKS addresses come from trusted application configuration, never
  from an unverified token. Audience requirements need a separate agreed IdP
  contract; these commits do not invent a required audience.

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
- `email` and `full_name` are display copies of Central Identity information.
  The identity key is `user_id`, never email. Local email is **not unique** and
  must not have a unique database constraint (DOC-04 section 2.1.5).

## Administrator bootstrap

- The initial administrator is created exclusively through a trusted **non-web
  CLI / CommandLineRunner** execution, disabled during ordinary web startup.
  The operator identifies an existing active Central Identity account by UUID.
- There must be **no REST endpoint** for administrator bootstrapping.
- A dedicated singleton `lms_setup_state` row is created ahead of bootstrap.
  Bootstrap acquires a database-level pessimistic lock using
  `SELECT ... FOR UPDATE` on that row. A missing row must fail closed.
- Checking setup state, checking existing active administrators, creating or
  activating the initial local administrator, and marking setup complete occur
  within the same transaction. Concurrent processes serialize on the same row;
  an in-memory lock or a count of administrators alone is insufficient.
- Setup is strictly run-once: once any active administrator exists or setup is
  marked complete, bootstrap is permanently locked out. Existing installations
  with an active administrator must be recorded as complete. Later suspension,
  demotion, or deletion of administrators does not reopen bootstrap.
- Failed initialization rolls back both the user change and completion marker.
  Successful initialization persists the completion marker across restarts.

## Standard security error response (DOC-05 alignment)

All 401 and 403 responses use `Content-Type: application/json;charset=UTF-8`
and these five fields:

```json
{
  "timestamp": "2026-10-30T08:30:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "A valid Bearer access token is required.",
  "path": "/api/v1/classes"
}
```

`timestamp` is an ISO-8601 UTC timestamp. For 403, `status` is `403` and `error`
is `Forbidden`. `message` gives a safe explanation of the failure; `path` is
the request URI, without query parameters. Do not expose tokens, keys, raw
exception details, stack traces, or database information. Authentication errors
retain the HTTP Bearer challenge in `WWW-Authenticate`.

The supplied task explicitly selects this five-field security error envelope.
The older DOC-05 export uses `{code, message, details?}`; the approved task's
envelope takes precedence for 401/403 in this implementation.

## Delivery boundaries

- Commit 0 records this contract. Commit 1 implements JWT identity/claim
  validation and the JSON 401 entry point, with real signed-token tests.
- Local database role/status enforcement and the JSON 403 handler belong to
  Commit 2. Its five existing integration-test methods remain explicitly
  disabled until that implementation is delivered.
- Bootstrap and its database locking/state migration are subsequent work.
- The existing V2 migration, entity, and repository tests currently enforce
  unique local email, contrary to the approved projection contract above.
  Correcting that schema and its tests requires subsequent migration work;
  Commit 0/1 do not change already recorded migration checksums.
