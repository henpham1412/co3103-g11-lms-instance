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
| Database | PostgreSQL |
| Persistence | Spring Data JPA |
| Security | Spring Security and OAuth2 Resource Server |
| Validation | Spring Boot Validation |
| File Storage | Local or object storage, according to deployment configuration |

Spring Boot manages compatible dependency versions through its dependency management. Avoid adding explicit versions to individual Spring Boot-managed dependencies unless required and reviewed by the team.

The PostgreSQL version, database name, local connection settings, and file-storage configuration will follow the shared development baseline agreed upon by the team.

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
- **PostgreSQL** — Required for local database-backed development.

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

This command compiles and packages the application without executing tests. Run the available test suite separately when the required configuration and tests are ready.

### 5.4 Configure PostgreSQL

Each developer should use a local PostgreSQL database for development. Central Identity and each LMS instance use separate databases.

Before running the application, configure the LMS datasource settings in accordance with the shared local development configuration.

Do not commit local database passwords, signing keys, or other secrets. Keep machine-specific configuration separate from shared project files.

The exact database name, port, datasource properties, and schema migration workflow will be documented once the team finalizes the local development baseline.

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

### 5.6 Bootstrap the Initial Administrator

Use the [administrator bootstrap CLI](docs/administrator-bootstrap.md) once for a new LMS database.
It requires an explicitly enabled non-web execution and the verified Central Identity account UUID.
Ordinary web startup never executes bootstrap. Any existing Administrator, including
SUSPENDED access, closes setup under DOC-04 section 2.1.5. The closure records that
administrator's UUID; subsequent attempts cannot rewrite it or reopen setup.
V1 permits duplicate display emails, V2 widens columns without adding uniqueness,
and V3 creates/seeds setup state. Local identity remains keyed by UUID.

### 5.6 Local Learning-Material Storage

The learning-content API stores uploaded files outside the repository. By default, files are written to `./var/lms-files`; set `LMS_LOCAL_STORAGE_DIRECTORY` to choose another directory. Keep this directory persistent and include it in deployment backups.

For `DOCUMENT` and uploaded `VIDEO` materials, send multipart form data to `POST /api/v1/lessons/{lessonId}/materials` with a JSON `metadata` part and a `file` part. Documents are limited to 50 MiB (PDF, DOCX, PPTX); uploaded videos are limited to 200 MiB (MP4, WebM). The server checks file contents and serves stored files through the authenticated URL returned as `file_url`. Students must have active class membership and the material must be published; tutors must own the class.

This local-storage implementation does not persist extra checksum/MIME/size metadata columns. The current database schema stores the generated file URL only. External `URL` and HTTPS video links are still submitted as JSON.

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

All LMS API endpoints use the `/api/v1` prefix and are served by the current LMS instance.

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
