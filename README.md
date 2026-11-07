# CO3103 Group 11 — LMS Instance

The backend of **A Self-Hosted Learning Platform with Centralized Authentication**.

The LMS Instance provides the learning and management functions for an individual self-hosted LMS deployment. It manages local user access, classes, learning content, assessments, learning progress, interaction, notifications, adaptive learning, and LMS administration.

Each deployed LMS instance maintains its own configuration, PostgreSQL database, and learning-file storage. The repository contains the shared application source code used to build and deploy these instances.

## 1. Technology Stack

| Component | Version / Technology |
|---|---|
| Language | Java 25 |
| Framework | Spring Boot 4.1.1 |
| Build Tool | Apache Maven 3.9.12 via Maven Wrapper |
| Maven Wrapper | 3.3.4 (`only-script`) |
| Database | PostgreSQL 18.6 |
| Persistence | Spring Data JPA |
| Security | Spring Security and OAuth2 Resource Server |
| Validation | Spring Boot Validation |
| File Storage | Local or object storage, according to deployment configuration |

Spring Boot manages compatible dependency versions through its dependency management. Avoid adding explicit versions to individual Spring Boot-managed dependencies unless required and reviewed by the team.

DOC-07 §§3.2–3.3 specifies PostgreSQL **18.6** and the development database
`lms_instance_dev`. File storage is configured separately for each deployment.

## 2. Responsibilities

The LMS Instance is responsible for:

- Validating access tokens issued by Central Identity.
- Managing LMS-local user access records, roles, and access status.
- Creating and managing private classes and class memberships.
- Organizing learning content into chapters, lessons, materials, and activities.
- Managing quiz and assignment configuration, attempts, submissions, grading, and feedback.
- Tracking lesson completion, assessment outcomes, and learning progress.
- Supporting discussions and in-app notifications.
- Providing rule-based adaptive learning and personalized activity recommendations.
- Providing LMS-local administration, reported-content review, basic reports, and audit logs.

### Authentication and Authorization Boundary

Central Identity authenticates users and issues signed access tokens. The LMS validates the token and performs local authorization checks before granting access to protected resources.

The supported local LMS roles are:

- `STUDENT`
- `TUTOR`
- `ADMINISTRATOR`

The current LMS uses `LmsUserAccess` to determine the user's local role and access status. Class membership and resource-level permissions are checked separately.

A centralized identity may have different roles and access statuses in different LMS instances. LMS-local administration must not modify the global account status maintained by Central Identity.

## 3. Main Modules

| Module | Responsibility |
|---|---|
| Class Management | Class information, discovery, membership, and enrollment requests |
| Learning Content | Chapters, lessons, materials, and activity placement and visibility |
| Assessment | Quizzes, assignments, attempts, submissions, grading, and feedback |
| Learning Progress | Lesson completion and last-visited lesson tracking |
| Adaptive Learning | Skill topics, prerequisites, mastery estimation, and activity recommendations |
| Interaction & Notifications | Class discussions and in-app notifications |
| Administration | Local user access management, class administration, content-report review, basic reports, and audit logs |

Adaptive Learning recommendations are integrated into existing Student learning and progress experiences. The MVP does not require a separate Adaptive Learning dashboard.

These module descriptions define the intended scope. A feature should be considered implemented only when its functionality is available in the source code.

## 4. Prerequisites

Install the following software:

- **JDK 25** — Required to compile and run the application.
- **Git** — Required to clone and update the repository.
- **PostgreSQL 18.6** — Required for local database-backed development; include its `bin` directory on `PATH`.
- **ffprobe** (provided by FFmpeg) — Required for server-side MP4/WebM upload validation.
  Ensure `ffprobe -version` works in the backend environment. Set `LMS_FFPROBE_EXECUTABLE`
  to its executable path if it is not on `PATH`.

A separate global Maven installation is not required. The repository includes Maven Wrapper scripts that download and use the configured Maven version.

Verify the Java installation:

```bash
java -version
javac -version
```

Both commands should report Java 25.

## 5. Getting Started

### 5.1 Clone the Repository

```bash
git clone <repository-url>
cd co3103-g11-lms-instance
```

Replace `<repository-url>` with the GitHub URL of this repository. Access to a private repository must be granted by its owner.

### 5.2 Verify Maven Wrapper

**Windows PowerShell**

```powershell
.\mvnw.cmd --version
```

**macOS / Linux**

```bash
./mvnw --version
```

The output should identify Apache Maven 3.9.12 and the Java runtime used by Maven.

On the first execution, Maven Wrapper may download the configured Maven distribution. An Internet connection is required if the distribution is not already available in the local cache.

### 5.3 Build the Project

**Windows PowerShell**

```powershell
.\mvnw.cmd -DskipTests package
```

**macOS / Linux**

```bash
./mvnw -DskipTests package
```

This command compiles and packages the application without executing tests. Run the
full test suite using the disposable database instructions in §5.8 before committing changes.

### 5.4 Configure PostgreSQL

Use PostgreSQL **18.6** with a UTF-8 database. Central Identity uses
`central_identity_dev`; this repository uses the separate `lms_instance_dev`
database (DOC-07 §§3.2–3.3). Verify the server version and create the database:

```powershell
psql -h localhost -p 5432 -U postgres -d postgres -c "SHOW server_version;"
createdb -h localhost -p 5432 -U postgres --encoding=UTF8 lms_instance_dev
```

The same PostgreSQL client commands work in macOS/Linux shells. The database
creator needs `CREATEDB` or administrator privileges. The role used by the
application must own the database/schema or have permission to create schemas,
tables, constraints, and indexes, alter migrated objects, and read/write tables.
Migration ownership is required on upgrades as well as fresh installations.

Every application/Flyway pool connection sets PostgreSQL `log_error_verbosity=terse`,
disables failing-statement and statement/duration sampling logs, and disables error
parameter logging. This suppresses constraint-failure row `DETAIL` and SQL/parameter
copies in the server log. SQLSTATE and safe primary constraint errors remain available.
The pool fails to start if these settings cannot be applied. For a non-superuser
database owner, a database administrator must grant these narrowly scoped permissions
(replace `lms_app` with the actual application role):

```sql
GRANT SET ON PARAMETER log_error_verbosity, log_min_error_statement,
  log_parameter_max_length_on_error, log_statement, log_min_duration_statement,
  log_min_duration_sample, log_transaction_sample_rate TO lms_app;
```

Use the same policy for other database clients and log collectors. `terse` removes
DETAIL, HINT, QUERY and CONTEXT; it does not redact arbitrary primary error messages
from custom SQL/functions. Keep PII out of those messages and restrict database-log
access and retention. See [PostgreSQL logging settings](https://www.postgresql.org/docs/18/runtime-config-logging.html).

Set the datasource variables in the shell that runs Maven; `.env` files are not
loaded automatically by the application:

| Variable | Default / purpose |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/lms_instance_dev` |
| `DB_USERNAME` | `postgres`; use the role that owns the local database |
| `DB_PASSWORD` | Required; password for that role |

**Windows PowerShell**

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/lms_instance_dev'
$env:DB_USERNAME = 'postgres'
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host 'Local PostgreSQL password' -AsSecureString)).Password
```

**macOS / Linux (Bash)**

```bash
export DB_URL='jdbc:postgresql://localhost:5432/lms_instance_dev'
export DB_USERNAME='postgres'
read -rsp 'Local PostgreSQL password: ' DB_PASSWORD; echo
export DB_PASSWORD
```

Flyway runs `src/main/resources/db/migration` on application startup: V1 creates
local access, V2 aligns columns, V3 initializes administrator setup, V4 creates
classes and memberships, V5 creates learning-content tables, V6 adds the default
admin-listing index, V7 adds independent profile/login token issuance watermarks,
and V8 creates audit logs. An empty database receives V1 through V8. A database
with the official main V5 learning-content history upgrades through V6 through V8
without changing existing migrations or data.

Before upgrading an existing database, inspect its Flyway history. A database that
already applied the PR's former `V4__create_audit_logs.sql` has an incompatible V4
history: renaming that script to V8 is not an in-place upgrade. Retained databases
require a separately reviewed data/history transition and rehearsal; disposable
databases may be recreated only with the owner's authorization. Do not repair,
clean, baseline, disable validation, or edit history to conceal a mismatch.

The nullable watermarks store exact epoch seconds (`NUMERIC(21,9)`) to preserve
decoded JWT nanoseconds. Existing rows initialize them on the first trusted token
after upgrade; historical issuance cannot be inferred from local wall-clock timestamps.
Hibernate does not create/update the schema (`ddl-auto=none`). Add new versioned
migrations for schema changes; do not edit applied migrations or repair checksums
to bypass validation. Do not commit database passwords, signing keys, or other secrets.

### 5.5 Run the Application

After PostgreSQL is available and the required datasource and security configuration have been provided, run:

**Windows PowerShell**

```powershell
.\mvnw.cmd spring-boot:run
```

**macOS / Linux**

```bash
./mvnw spring-boot:run
```

The application listens on `http://localhost:8081` by default; set `SERVER_PORT` to override the port.
Central Identity remains on `http://localhost:8080` by default.
Set `CENTRAL_IDENTITY_ISSUER_URI` and `CENTRAL_IDENTITY_JWKS_URI` when the identity
service uses different endpoints; the defaults are `http://localhost:8080` and
`http://localhost:8080/.well-known/jwks.json`. See the
[security contract](docs/security-contract.md) for token and authorization requirements.

`lms.allowed-origins` (environment variable `LMS_ALLOWED_ORIGINS`) configures exact
browser origins for credentialed cross-origin requests. It defaults to
`http://localhost:3000,http://localhost:5173`. Set, for example,
`LMS_ALLOWED_ORIGINS=https://portal.example.edu` to replace those defaults. Entries
are comma-separated, trimmed, and deduplicated; invalid or blank origins fail startup.
Use HTTP(S) scheme, host, and optional port (1-65535), without userinfo, path,
trailing slash, query, fragment, or wildcard. Allowed methods are `GET`, `POST`,
`PUT`, `DELETE`, `PATCH`, and `OPTIONS`.
Valid CORS preflight requests are handled before Bearer authentication and may be cached for
3600 seconds. Ordinary OPTIONS requests also return 200 without authentication or database
access. Disallowed CORS requests receive the standard JSON 403 envelope. Other API requests
require a valid access token and active LMS-local access.

Class thumbnail uploads are stored under `lms.storage.class-thumbnails.directory`
(environment variable `LMS_CLASS_THUMBNAIL_DIRECTORY`), which defaults to
`./var/class-thumbnails` and is git-ignored. Stored files are served from
`/class-thumbnails/**` without authentication, and their public URLs are built from
`lms.storage.class-thumbnails.public-base-url` (`LMS_PUBLIC_BASE_URL`, default
`http://localhost:8081`). Set `LMS_PUBLIC_BASE_URL` to the base address browsers can
reach when the instance runs behind a gateway or on another host; the default value is
development-only.

### 5.6 Bootstrap the Initial Administrator

Use the [administrator bootstrap CLI](docs/administrator-bootstrap.md) once for a new LMS database.
It requires an explicitly enabled non-web execution and the verified Central Identity account UUID.
Ordinary web startup never executes bootstrap. Any existing Administrator, including
SUSPENDED access, closes setup under DOC-04 section 2.1.5. The closure records that
administrator's UUID; subsequent attempts cannot rewrite it or reopen setup.
V1 permits duplicate display emails, V2 widens columns without adding uniqueness,
and V3 creates/seeds setup state. Local identity remains keyed by UUID.

### 5.7 Local Learning-Material Storage

The learning-content API stores uploaded files outside the repository. By default, files are written to `var/lms-files` under the process working directory; set `LMS_STORAGE_LOCAL_DIRECTORY` to choose another directory. The default is relative, so a service started from a different working directory resolves it against that directory — give deployments an absolute path. Keep this directory persistent and include it in deployment backups.

For `DOCUMENT` and uploaded `VIDEO` materials, send multipart form data to `POST /api/v1/lessons/{lessonId}/materials` with a JSON `metadata` part and a `file` part. Documents are limited to 50 MiB (PDF, DOCX, PPTX); uploaded videos are limited to 200 MiB (MP4, WebM). The server checks file contents and serves stored files through the authenticated URL returned as `file_url`. Students must have active class membership and the material must be published; tutors must own the class.

This local-storage implementation does not persist extra checksum/MIME/size metadata columns. The current database schema stores the generated file URL only. External `URL` and HTTPS video links are still submitted as JSON.

Uploaded PDF/Office documents are parsed with PDFBox/Apache POI. Video uploads are
demuxed with ffprobe and must contain actual video packets in an MP4 or WebM
container; audio-only MP4, malformed containers and header-only files are rejected
with `415 UNSUPPORTED_MEDIA_TYPE`. The application does not impose a codec
allowlist. Video probing has a 30-second timeout and bounded diagnostic output;
a missing ffprobe installation is a server storage error, not an accepted upload.
Password-protected PDFs remain encrypted uploads; the server recognizes their
container but does not decrypt their page contents.

### 5.8 Run the Full Test Suite

Use JDK 25 and a **disposable PostgreSQL 18.6 database**, separate from development
or production data. Tests create schemas, run Flyway migrations, insert/delete
fixtures, alter constraints, and drop test schemas. The test role must own this
database and its test objects; superuser access is not required.
The test role also needs the logging-parameter SET grants listed in §5.4.

After setting `DB_USERNAME` and `DB_PASSWORD` as above:

**Windows PowerShell**

```powershell
createdb -h localhost -p 5432 -U postgres --encoding=UTF8 lms_instance_test
$env:DB_URL = 'jdbc:postgresql://localhost:5432/lms_instance_test'
.\mvnw.cmd test
```

**macOS / Linux (Bash)**

```bash
createdb -h localhost -p 5432 -U postgres --encoding=UTF8 lms_instance_test
export DB_URL='jdbc:postgresql://localhost:5432/lms_instance_test'
./mvnw test
```

These examples use `postgres` as the database owner; adjust `-U`, host, port, and
`DB_URL` together for a different local setup. The tests supply their own JWT/JWKS
fixtures, so a running Central Identity service is not required. Expect
`BUILD SUCCESS` with zero failures, errors, and skips. Restore `DB_URL` to
`lms_instance_dev` before starting the development application. See the
[user administration verification guide](docs/admin-user-management.md) and
[bootstrap guide](docs/administrator-bootstrap.md) for feature-specific checks.

## 6. Maven Wrapper Configuration

The repository uses the following settings in `.mvn/wrapper/maven-wrapper.properties`:

```properties
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.12/apache-maven-3.9.12-bin.zip
```

- `wrapperVersion` identifies the Maven Wrapper version.
- `distributionType=only-script` uses the wrapper scripts without requiring `maven-wrapper.jar`.
- `distributionUrl` specifies the Apache Maven distribution used by the project.

Keep the wrapper scripts and configuration committed to version control so all team members use the same Maven distribution.

A separate global Maven installation is not required. A compatible JDK installation is still necessary.

Do not modify these shared settings locally unless the team agrees to update the project baseline.

## 7. API Overview

LMS-13 user administration follows the DOC-05 `/api/v1/admin/users` contract,
with `/api/admin/users` retained as a route alias. Both routes use snake_case
responses and one-based pagination; see the
[UC7.1 endpoint and verification guide](docs/admin-user-management.md) and its
[Postman collection](docs/postman/LMS-13_AdminUserManagement.postman_collection.json).
All LMS endpoints are served by the current LMS instance.

Protected endpoints require a valid Bearer access token:

```http
Authorization: Bearer <access_token>
```

The API specification in DOC-05 defines the request and response contracts, roles, permissions, validation rules, and important error cases.

Main API areas include:

- **Class and Content APIs:** Classes, memberships, chapters, lessons, materials, and activities.
- **Assessment and Progress APIs:** Quiz configuration, attempts, assignments, submissions, grading, feedback, and learning progress.
- **Adaptive Learning APIs:** Recommended activities for eligible Students and mastery summaries for owning Tutors.
- **Interaction APIs:** Discussions and notifications.
- **Administration APIs:** Local user access, class administration, content reports, basic reports, and audit logs.

Refer to DOC-05 for the complete endpoint list and API contracts. The presence of an endpoint in the specification does not necessarily mean it has already been implemented.

## 8. Important Data and Business Rules

- `ClassMember` is the single entity representing a student's membership in a class. Do not introduce a separate `Enrollment` entity.
- Only an `ACTIVE` class membership grants access to protected class learning resources and activities.
- Leaving a class, rejecting a request, or removing a member updates the membership status rather than physically deleting the membership record.
- Learning Content manages activity placement, ordering, and visibility. Assessment manages assessment-specific configuration, attempts, submissions, grading, and results.
- Lesson completion is recorded explicitly. A last visit does not count as lesson completion.
- Quiz and assignment scores use a 0–100 percentage scale, while question points are raw assessment points.
- Class and content lifecycle operations must preserve the learning and audit history required by the project.
- Basic reports are derived from existing LMS data; a separate report entity is not required for the MVP.

Follow DOC-01 through DOC-06 and the current API contracts in DOC-05 when implementing or changing these rules.

## 9. Development Conventions

- Follow the repository's protected `main` branch and Pull Request review workflow.
- Use feature branches for development.
- Keep API contracts aligned with DOC-05.
- Keep entities, attributes, relationships, and constraints aligned with DOC-04.
- Preserve the boundary between centralized authentication and LMS-local authorization.
- Update relevant documentation when an agreed requirement, database design, or API contract changes.
- Never edit a Flyway migration that has already been applied to a database you or a
  teammate keeps; add the next `V<n>__*.sql` instead. `MigrationPolicyTest` pins the
  checksums of the accepted migrations and `.gitattributes` keeps `*.sql` byte-identical
  across checkouts. Recovering a local database that applied a superseded migration is an
  explicit decision - recreate it or run a reviewed Flyway repair - never an automated
  build step.
- Run the available build and test checks before submitting a Pull Request.

## 10. Project Documentation

The shared project documentation is maintained separately from source code:

- DOC-01 — Requirements & Use Cases
- DOC-02 — UI/UX Design
- DOC-03 — System Architecture
- DOC-04 — Database Design
- DOC-05 — API Specification
- DOC-06 — Adaptive Learning Design
- DOC-07 — Development Setup
