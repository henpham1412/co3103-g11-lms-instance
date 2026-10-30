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
UPDATE`), checks completion and active administrators, writes `ADMINISTRATOR` /
`ACTIVE` local access, and marks setup complete. Concurrent CLI processes wait
on the same database lock. Only the first eligible initialization can succeed.
A missing setup row or an already-completed setup fails closed.

If an active administrator already exists, the transaction commits completion
without changing any user. The runner then reports refusal outside that
transaction, preserving the permanent lockout. A suspended administrator alone
does not block a first bootstrap. Any initialization failure rolls back user and
setup changes together.

The completion marker survives application restarts, suspension, demotion, and
deletion of administrators. Deleting the recorded administrator clears only the
nullable `bootstrapped_by` reference. Bootstrap is not an administrator recovery
mechanism; it does not recreate a missing setup row or reset completed setup.

V3 also removes V2's unique email constraint without changing historical migration
checksums. Automated tests run V3 on PostgreSQL and native H2 (starting from a
V2-equivalent schema, since the historical migrations use PostgreSQL syntax),
and verify locking, rollback, restart persistence, and web/CLI boundaries.
