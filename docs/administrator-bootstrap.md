# Administrator bootstrap

Bootstrap runs once per LMS database using a trusted local operator command.
The operator must verify that `user-id` belongs to an active Central Identity
account. This command creates or activates its **local LMS access**; it does not
create a Central Identity account or authenticate against the IdP.

Supply the usual `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` environment variables
for the target LMS database. Build with JDK 25, then run in PowerShell:

```powershell
./mvnw.cmd -DskipTests package
java -jar target/co3103-g11-lms-instance-0.0.1-SNAPSHOT.jar `
  --spring.main.web-application-type=none `
  --lms.bootstrap.admin.enabled=true `
  --lms.bootstrap.admin.user-id=123e4567-e89b-12d3-a456-426614174000 `
  "--lms.bootstrap.admin.full-name=Initial Administrator" `
  --lms.bootstrap.admin.email=administrator@example.com
```

Replace the example UUID and profile with the verified account's values.
`user-id` must be a full canonical UUID without surrounding whitespace; uppercase
and lowercase hexadecimal are accepted. For a new local record, `full-name`
must be nonblank with at most 150 characters, and `email` must be a valid,
nonblank email with at most 255 characters. For an existing UUID, omit the name
and email; its stored display copies are preserved even if arguments are supplied.
Email is nonunique and is never used to select, merge, or promote an account.

Both the enable flag and a non-web application context are required. Ordinary
web startup registers neither the runner nor its service, including when the
flag is accidentally enabled. There is no bootstrap HTTP endpoint. Successful
CLI execution closes the application context and exits; refused or failed
initialization exits unsuccessfully.

Flyway V3 creates and seeds the singleton `GLOBAL` row before bootstrap. Within
one transaction the service locks it with `PESSIMISTIC_WRITE` (`SELECT ... FOR
UPDATE`), checks completion and existing administrators, writes `ADMINISTRATOR` /
`ACTIVE` local access, and marks setup complete. Concurrent CLI processes wait
on the same database lock. Only the first eligible initialization can succeed.
A missing setup row or an already-completed setup fails closed.

If any administrator already exists (ACTIVE or SUSPENDED), bootstrap refuses,
consistent with DOC-04 section 2.1.5. The transaction first locks the selected
administrator and commits completion with that account's UUID without changing
any user. The nontransactional runner then reports refusal, preserving the permanent
lockout. This committed seal is distinct from an initialization failure, which rolls
back user and setup changes together. Retrying even the same UUID after completion
fails without changing the stored UUID, timestamp, or user.

`bootstrapped_by` identifies the local administrator associated with closure, not
the authenticated deployment operator. New initialization records the initialized
account. Reconciliation and migration select an existing administrator by ascending
`created_at`, then `user_id`; this is a stable representative, not proof of the
historically first administrator. `completed_at` records when setup was closed.

The GLOBAL lock serializes bootstrap and access updates through `AdminUserService`.
Access updates acquire it before reading the target or counting active administrators
so concurrent demotions or suspensions cannot remove the last active administrator.
They preserve the setup completion marker. Any future provisioning path must lock
GLOBAL before affected user rows and respect completed setup. This lock does not
serialize arbitrary manual user-table writes. Keep external administrator writers
quiescent during migration and initial setup.

The completion marker survives application restarts, suspension, demotion, and
deletion of administrators. Deleting the recorded administrator clears only the
nullable `bootstrapped_by` reference. Bootstrap is not an administrator recovery
mechanism; it does not recreate a missing setup row or reset completed setup.

V1 already permits duplicate local email copies. V2 widens columns without adding
uniqueness. V3 introduces and seeds setup state, sealing it with an existing
administrator's UUID regardless of access status. This follows DOC-04 section
2.1.5's UUID identity/display-copy model; Central Identity's unique email is a
separate schema rule.

Native H2 tests execute V3 from an approximation of the final V2 schema. PostgreSQL
tests cover the real V1-to-V3 migration history, locking, rollback, restart
persistence, duplicate emails, and web/CLI boundaries. Passing H2 tests alone does
not establish PostgreSQL concurrency or full servlet integration correctness.

The revised V3 is intended for the unmerged feature-branch migration path. If a
retained database applied an earlier V3, its checksum differs: preserve the applied
migration and plan an explicit forward migration before upgrading that database.
Do not automatically repair Flyway history or reset completed setup.
