# MVP-0 — Read-only implementation audit

**Project:** CO3103 Group 11 — Self-Hosted Learning Platform with Centralized Authentication<br>
**Audit date:** 29 October 2026<br>
**Issue:** [LMS Instance #6 — MVP-0](https://github.com/henpham1412/co3103-g11-lms-instance/issues/6)

**Scope:** All three repositories, the seven attached project documents, existing issues, merged pull requests, and available GitHub Actions results.

This report records the completed read-only audit. It is a point-in-time assessment of the commits below, not a claim about subsequent implementation. Publishing this report is a documentation-only follow-up; the audit did not change application code, migrations, tests, branches, pull requests, or issues.

## 1. Executive summary

**The repositories contain a working development foundation, but the main MVP business features are not implemented at the audited commits.**

The completed foundation includes:

- Frontend application setup, routing, separate Axios clients, and lint/build CI.
- Central Identity's initial four-table migration and application/schema smoke tests.
- LMS's initial local-access table, stateless JWT Resource Server configuration, and authentication-rejection smoke tests.
- Successful GitHub Actions runs at all three audited commits.

The main findings are:

- **Central Identity:** Authentication, profile, password recovery, client validation, and JWT issuance classes are empty.
- **LMS:** Class and chapter classes are empty. Assessment, progress, adaptive learning, interaction, notification, and administration implementations are absent.
- **Frontend:** The application displays an environment-ready page. Feature screens, authentication state, protected routes, and actual API calls are absent.
- **Authorization:** LMS authenticates requests through Spring Security, but does not resolve or enforce local access status, local roles, class membership, or resource ownership.
- **Integration:** No complete login-to-learning workflow exists.

**Verification:** Frontend lint and production build passed locally. Backend verification was attempted but stopped during dependency resolution, before JUnit execution. Separately, GitHub reports successful backend CI runs at the audited commits.

Most missing functionality already has an appropriate open issue. Existing setup work should be retained rather than repeated. Independent feature development need not wait for completion of unrelated audit follow-ups.

## 2. Repository and document baseline

### 2.1 Primary repository baseline

The primary audit baseline is GitHub `main` at these commits:

| Repository | Branch | Audited commit SHA |
|---|---|---|
| [Frontend](https://github.com/henpham1412/co3103-g11-frontend/tree/ce5e6674e0426a66dc45a7ca737faad00bc28ce2) | `main` | `ce5e6674e0426a66dc45a7ca737faad00bc28ce2` |
| [Central Identity](https://github.com/henpham1412/co3103-g11-central-identity/tree/ab67f9625c29e03327d3ef9461db5ff62f8679ef) | `main` | `ab67f9625c29e03327d3ef9461db5ff62f8679ef` |
| [LMS Instance](https://github.com/henpham1412/co3103-g11-lms-instance/tree/c4dc88c538f23f939e5f8cc44df968512d55f778) | `main` | `c4dc88c538f23f939e5f8cc44df968512d55f778` |

The local `work` branches were clean but older than GitHub `main`:

| Repository | Local branch | Local SHA | Treatment |
|---|---|---|---|
| Frontend | `work` | `245419a927f95c1f58f9f2f1966a9a9ec8abde5b` | Inspected; not the final implementation baseline |
| Central Identity | `work` | `5bdc2c7c47c7edac36203e8186dc56ab8497ea23` | Inspected; not the final implementation baseline |
| LMS Instance | `work` | `3edaa85a658adddc3a821bf4dcba5dbaa40ac408` | Inspected; not the final implementation baseline |

GitHub snapshots were retrieved without updating those checkouts. Downloaded files matched their Git blob hashes: Frontend **28/28**, Central Identity **41/41**, and LMS Instance **25/25**.

All three remote `main` SHAs were checked again at the end of the audit and remained unchanged. The original checkouts remained clean. Checks ran against separate audit copies.

### 2.2 Documents inspected

| Document | Audit use |
|---|---|
| DOC-01 — Requirements & Use Cases | US1–US7, use cases, business rules, MVP scope |
| DOC-02 — UI/UX Design | Required screens and user flows |
| DOC-03 — System Architecture | Authentication boundary, module responsibilities, integration |
| DOC-04 — Database Design | Entities, relationships, constraints, retention |
| DOC-05 — API Specification | Endpoints, payloads, permissions, errors |
| DOC-06 — Adaptive Learning Design | Evidence calculation, thresholds, recommendations, evaluation |
| DOC-07 — Development Setup | Configuration, startup order, bootstrap, quality checks |

**Approval limitation:** DOC-07 §6.2 describes “Version 1.0 — Pending final team review and confirmation.” The attached DOCX files were used as the supplied requirements baseline; formal approval/freeze could not be established. Live Google Docs revisions and Figma screen fidelity were not verified.

The attached document bytes can be identified by these SHA-256 values:

| Attached DOCX | SHA-256 |
|---|---|
| Requirements & Use Cases — Group 11.docx | `01ae9b11fcdeb83252f719bc7bd406b931bff677f95f604a9b41e48bfbda7445` |
| UI UX Design — Group 11.docx | `a998aae7a16fbc42ce99f2f76a6e3459a59d46b0c91aa686b48fd9530d025a57` |
| System Architecture — Group 11.docx | `756d8a731ed68eb461ac482d753aa87f31ae215909b9b289a0dced18c1319598` |
| Database Design — Group 11.docx | `038caf5aad537dbb716748234cd594d690c86adbc241a100131d23c5bce74899` |
| API Specification — Group 11.docx | `97a76b7c3dd38a487cfbaeb59c6d1062aff6416b79ab1f0ebc3d80cb83d666f5` |
| Adaptive Learning Design — Group 11.docx | `f21be9370a2c92fa76b36bf57bf667d15bc878f0f317e77615e50123fe03dbce` |
| Development Setup — Group 11.docx | `5a154e13870653ed0f221d260e00bc8d9701b81304e154c22bb6679c43f4504d` |

### 2.3 Evidence path notation

All source paths are repository-relative at the primary audited commits:

- `I/` expands to `src/main/java/vn/edu/hcmut/co3103/identity/` in Central Identity.
- `L/` expands to `src/main/java/vn/edu/hcmut/co3103/lms/` in LMS Instance.
- `IT` means `src/test/java/vn/edu/hcmut/co3103/identity/Co3103G11CentralIdentityApplicationTests.java`.
- `LT` means `src/test/java/vn/edu/hcmut/co3103/lms/Co3103G11LmsInstanceApplicationTests.java`.

For example, `I/service/AuthService.java` identifies the exact path `src/main/java/vn/edu/hcmut/co3103/identity/service/AuthService.java`.

Issue identifiers refer to their respective repositories: CI-* in Central Identity, FE-* in Frontend, and LMS-*, MVP-0, OPS-1, QA-*, REL-1 in LMS Instance.

## 3. MVP gap matrix

### 3.1 Status definitions

| Status | Meaning |
|---|---|
| **Done** | The specifically described scope is implemented and supported by the stated verification evidence |
| **Partial** | Some functional implementation exists, but required behavior remains incomplete or unverified |
| **Missing** | Required behavior is absent from the inspected implementation; empty placeholders do not count |
| **Blocked** | A specific assessment or verification cannot be completed because of an identified dependency or uncertainty |

Tests are referenced as **T1–T6** in Section 7. Missing-feature findings come from source inspection, not from inability to execute tests. “Done” for setup does not mean the corresponding MVP business feature is complete.

### 3.2 Central Identity and authentication

| Requirement / use case | Repository | Status | Exact implementation evidence | Missing behavior / uncertainty | Proposed next action |
|---|---|---|---|---|---|
| Initial identity database foundation — DOC-04 §2.1 | Identity | **Done** for initial migration scope | `src/main/resources/db/migration/V1__create_central_identity_schema.sql` creates `users`, `user_profiles`, `password_resets`, `lms_clients`; `IT.flywayMigrationCreatesExpectedTables()` checks four table names | Schema foundation does not implement account behavior. Local migration execution blocked: T4; remote CI success: T6 | Retain migration and baseline tests; extend through feature work |
| Registration — UC1.1, DOC-05 §2.2 | Identity | **Missing** | `I/controller/AuthController.java`, `I/service/AuthService.java`, `I/entity/User.java`, `I/repository/UserRepository.java`, `I/dto/auth/request/RegisterRequest.java` are empty | No `POST /api/v1/auth/register`, validation, normalization, hashing, account creation, or confirmation-email implementation | Implement CI-2 #7; clarify password/email policy |
| Login and JWT issuance — UC1.2, DOC-05 §2.3 | Identity | **Missing** | `I/service/AuthService.java`, `I/security/jwt/JwtService.java`, `I/config/JwtProperties.java`, `I/dto/auth/request/LoginRequest.java`, `I/dto/auth/response/AuthResponse.java` are empty | No login endpoint, credential check, global-status enforcement, lockout, signing, `sub`/`exp`, or expiry response | Implement CI-2 #7, coordinated with LMS-2 |
| Registered LMS client validation — DOC-03 §3, DOC-05 §2.3 | Identity | **Missing** | `I/service/LmsClientService.java`, `I/entity/LmsClient.java`, `I/repository/LmsClientRepository.java` are empty; migration contains registry fields | No active-client lookup or redirect allowlist enforcement | Include explicitly in CI-2 #7; define registration procedure |
| Password recovery/reset — UC1.4 | Identity | **Missing** | `I/service/PasswordResetService.java`, `I/entity/PasswordReset.java`, `I/repository/PasswordResetRepository.java`, `I/dto/auth/request/ForgotPasswordRequest.java`, `I/dto/auth/request/ResetPasswordRequest.java` are empty | No recovery/reset endpoints, email delivery, secure token generation, expiry/use checks, or atomic consumption | Implement CI-3 #8 |
| Current profile — UC1.5 | Identity | **Missing** | `I/controller/CurrentUserController.java`, `I/service/UserService.java`, `I/entity/UserProfile.java`, `I/dto/user/request/UpdateProfileRequest.java`, `I/dto/user/response/CurrentUserResponse.java`, `I/dto/user/response/UserProfileResponse.java` are empty | No `GET/PATCH /api/v1/users/me`; optional-profile handling and validation absent | Implement CI-3 #8 |
| Client-side logout — UC1.3 | Frontend | **Missing** | `src/app/App.tsx`, `src/app/router.tsx`, `src/api/http.ts` contain no authentication state or logout operation | No token/cache clearing or logout navigation | Implement FE-1 #3; no server logout endpoint required |

### 3.3 JWT validation, local access control, and RBAC

| Requirement / use case | Repository | Status | Exact implementation evidence | Missing behavior / uncertainty | Proposed next action |
|---|---|---|---|---|---|
| JWT authentication foundation — DOC-05 §1.4 | LMS | **Partial** | `L/config/SecurityConfig.java::securityFilterChain()` configures stateless sessions, `anyRequest().authenticated()`, JWT Resource Server; `src/main/resources/application.yaml` configures issuer/JWKS | Real Identity-issued tokens, expiry, incorrect signatures and issuer handling not verified. Identity does not issue tokens or expose keys | Extend under LMS-2 #7; coordinate signing/key contract with CI-2 |
| Initial local-access schema — DOC-04 §2.1.5 | LMS | **Done** for initial migration scope | `src/main/resources/db/migration/V1__create_lms_user_access.sql`; `LT.flywayMigrationCreatesLmsUserAccessTable()` | No mapped access entity/repository or enforcement service | Retain migration; implement access resolution under LMS-2 |
| Local access status, roles and ownership — BR-1.3, DOC-04 §4.1 | LMS | **Missing** | `L/config/SecurityConfig.java` only requires authentication; no local-user lookup, role check, membership check or ownership guard exists in the source tree | Valid JWT alone is insufficient under the requirements; current configuration does not implement that boundary | Complete LMS-2 #7; add role/status/ownership tests |
| Initial Administrator bootstrap and preservation — DOC-03 §2.6, DOC-04 §2.1.5 | LMS | **Missing** | Migration includes `ADMINISTRATOR`; no bootstrap command/service or last-admin guard exists | No trusted one-time initialization, atomic duplicate prevention or last-active-admin protection | Bootstrap in LMS-2 #7; last-admin protections in LMS-13 #18 |

### 3.4 Classes, content, assessment and progress

| Requirement / use case | Repository | Status | Exact implementation evidence | Missing behavior / uncertainty | Proposed next action |
|---|---|---|---|---|---|
| Class creation, catalog, details and lifecycle — UC2.1–2.5 | LMS | **Missing** | `L/controller/classmanagement/ClassController.java`, `L/service/classmanagement/ClassService.java`, `L/entity/Class.java`, `L/repository/classmanagement/ClassRepository.java`, `L/mapper/ClassMapper.java` are empty | No mapped APIs, persistence, search/pagination, immutable code, capacity checks, archive/restore or thumbnail upload | LMS-3 #8; UI in FE-3 #5 and FE-4 #6 |
| Membership and retained history — UC5.1–5.4 | LMS | **Missing** | Complete source/migration trees contain no `ClassMember` implementation/schema; `ClassService` is empty | No join/request/approve/reject/withdraw/leave/remove transitions, seat concurrency or record reuse | LMS-3 #8 using the single `ClassMember` entity |
| Chapters, lessons, ordering and hierarchical visibility — UC3.1–3.4 | LMS | **Missing** | `L/controller/learningcontent/ChapterController.java`, `L/service/learningcontent/ChapterService.java`, `L/entity/Chapter.java`, `L/repository/learningcontent/ChapterRepository.java` are empty | No lesson/material/activity implementation, ordering APIs, visibility inheritance or deletion restrictions | LMS-4 #9 and FE-5 #7 |
| Material upload and access — UC3.3, BR-3.2/3.5 | LMS | **Missing** | No material/storage implementation; `src/main/resources/application.yaml` has no application file-storage settings | MIME inspection, document/video limits, HTTPS validation, partial-upload cleanup and authorized file access absent | LMS-4 #9 and OPS-1 #21 |
| Quiz authoring, timed attempts and grading — UC4.1, 4.2, 4.5 | LMS | **Missing** | No assessment controllers/services/entities/migrations; only empty Class/Chapter placeholders exist | No configuration, questions, answers, attempt limits, timing, auto-submit, scoring, edit locking or regrading | LMS-5 #10 and FE-6 #8 |
| Assignments, submissions, grading and feedback — UC4.3, 4.4, 4.6 | LMS | **Missing** | No assignment/submission implementation or migration exists | No deadlines, file replacement, graded-submission lock, version conflicts, score/feedback persistence | LMS-6 #11 and FE-7 #9 |
| Completion, history, resume and tutor progress — UC4.7, UC5.5–5.10 | LMS | **Missing** | No `CompletionMark`, `LastVisit`, progress/history controllers or migrations; `LT` has only baseline tests | Explicit marks, visible-lesson denominator, re-login persistence, resume priority, private history and tutor views absent | LMS-7 #12 and FE-8 #10 |

### 3.5 Adaptive Learning, interaction and administration

| Requirement / use case | Repository | Status | Exact implementation evidence | Missing behavior / uncertainty | Proposed next action |
|---|---|---|---|---|---|
| Topics, prerequisites and associations — DOC-04 §2.4 | LMS | **Missing** | No `SkillTopic`, `SkillPrerequisite`, `LessonSkillTopic`, `ActivitySkillTopic` or `QuestionSkillTopic` implementation/migration | No class-scoped topics, associations, cycle rejection or cross-class checks | LMS-8 #13 after defining management contracts |
| Signals and mastery — DOC-06 §2.4 | LMS | **Missing** | No `LearningSignal`, `LearnerMastery`, calculation service or tests | Topic-specific quiz evidence, latest-three components, 50/80 thresholds, recalculation and deduplication absent | LMS-9 #14 |
| Recommendation and controlled evaluation — DOC-06 §§3–5 | LMS + Frontend | **Missing** | No recommendation endpoint/service; `src/app/router.tsx` has only `/` and fallback | No eligible selection, practice/fallback, visibility filtering, profile A/B comparison or fixed-baseline evaluation | LMS-9 #14, FE-9 #11; include evaluation evidence |
| Discussions — UC6.2 | LMS + Frontend | **Missing** | No discussion implementation/schema; no frontend discussion screen | Participation checks, same-context replies, edits, moderation and dependency-aware deletion absent | LMS-10 #15, FE-10 #12 |
| Notifications — UC6.1, BR-6.4/6.6 | LMS + Frontend | **Missing** | No notification entity/controller/service/job/schema or inbox | Event delivery, ownership, read state, reminder scheduling, deduplication and 90-day cleanup absent | LMS-12 #17, FE-11 #13 |
| Content reports and review — UC6.3, UC7.3 | LMS + Frontend | **Missing** | No `ContentReport` implementation/migration | Target authorization, duplicate prevention, review status, reviewer metadata and retention absent | LMS-11 #16, FE-10 #12, FE-12 #14 |
| Local user administration — UC7.1 | LMS + Frontend | **Missing** | Local-access migration only; no `/admin/users` implementation or administrative screens | Listing, role/status changes, transitions, last-admin protection and auditing absent | LMS-13 #18, FE-12 #14 |
| Administrative class management — UC7.2, DOC-05 §5.2 | LMS + Frontend | **Missing** | `ClassController`/`ClassService` empty; no `/admin/classes` implementation or admin class screen | Administrative inspection and allowed lifecycle updates have no explicit backlog owner | Assign within existing class/admin issues |
| Reports and audit logs — UC7.4–7.5 | LMS + Frontend | **Missing** | No report queries/endpoints, `AuditLog` implementation/migration or screens | No persisted-data aggregates, action records, retrieval or restrictions | LMS-14 #19, LMS-15 #20, FE-12 #14 |

### 3.6 Frontend integration and deployment

| Requirement / use case | Repository | Status | Exact implementation evidence | Missing behavior / uncertainty | Proposed next action |
|---|---|---|---|---|---|
| Routing, separate clients and quality baseline — FE-0 | Frontend | **Done** for setup scope | `src/app/router.tsx`, `src/app/NotFoundPage.tsx`, `src/api/http.ts::createApiClient()`, `src/api/centralIdentityClient.ts`, `src/api/lmsClient.ts`, `.github/workflows/ci.yml` | Feature routes absent. T1–T3 support setup only | Preserve completed FE-0 #1 |
| Actual API/authentication integration — DOC-07 API Integration | All three | **Partial** | Clients define URLs and timeout; no feature API calls reference them. Identity controllers empty | No Bearer attachment, 401 handling, callback, role resolution or payload integration | FE-1 #3, CI-2 #7, LMS-2 #7 |
| Local configuration and self-hosting — DOC-07 §3 | All three | **Partial** | Backend `application.yaml` files configure separate DB names/Flyway; frontend `.env.example` defines URLs; READMEs provide basic setup | Ports, signing, storage, client registration, bootstrap, sample data and reproducible combined startup incomplete | OPS-1 #21, then REL-1 #24 |
| Full core learning journey — DOC-01 §4 | All three | **Missing** | No implemented registration → class → content → assessment → progress chain | End-to-end execution blocked by absent features, not merely test environment | Retain QA-1 #22 and QA-2 #23 |
| Fresh local backend verification | Identity + LMS | **Blocked** | `pom.xml`, `IT`, `LT`; attempted `mvn verify` | Flyway artifacts unavailable locally; online resolution failed before tests | Restore dependency access; use isolated PostgreSQL |
| Formal baseline approval | Project documents | **Blocked** | DOC-07 §6.2 | Attached document explicitly says approval pending | Record reviewed version; continue independent work on clear contracts |

For absent modules, evidence is the complete inspected repository tree and migration inventory, not hypothetical filenames. No backend source contains an implemented REST controller or mapped business entity at these commits.

## 4. API contract, integration and security gaps

### 4.1 API contract gaps

| Finding | Evidence and impact | Recommended resolution |
|---|---|---|
| JWT signing/key-distribution contract incomplete | DOC-05 defines `sub` and `exp`, but not algorithm, key publication or operational key setup. LMS assumes `/.well-known/jwks.json`; Identity has no implementation | Agree issuer, algorithm, public-key distribution, required claims and lifetime between CI-2 and LMS-2. Do not treat the configured URL as implemented |
| Local user provisioning and role discovery undefined | DOC-04 requires `LmsUserAccess`; DOC-05 describes reading/updating existing users but not initial ordinary-user provisioning or frontend local-role lookup | Define trusted provisioning, initial role policy, unknown-user behavior, profile-copy synchronization and role lookup |
| Skill/topic management routes missing | LMS-8 requires management of topics/prerequisites/associations. DOC-05 §4.5 specifies output reads; questions accept topic IDs, but management contracts are absent | Extend reviewed contract before inventing endpoints |
| Password and lockout policies underspecified | DOC-01/DOC-05 refer to password policy/temporary lockout without concrete complexity, attempt threshold or duration | Agree configurable values and boundary behavior for CI-2/CI-3 |
| Content-report status inconsistency | DOC-01 UC6.3 says `PENDING_REVIEW`; BR-6.5, DOC-04 and DOC-05 use `PENDING` | Resolve explicitly; avoid silently adding a fifth status |
| Quiz edit/regrade confirmation incomplete | UC4.2 requires confirmation and affected-attempt information. PATCH routes do not define that exchange; question-add route does not explicitly enumerate BR-4.5's post-attempt addition restriction | Specify confirmation/conflict behavior; enforce BR-4.5 consistently |
| Submission concurrency incomplete | UC4.4 requires rejecting grading after a file replacement. DOC-04 includes `Submission.version`; DOC-05 grade payload has score/feedback only | Define expected-version or conditional-update semantics |
| Administrative class update contract broad | DOC-05 §5.2 says “permitted” updates without concrete field whitelist/transition matrix | Define allowed operations and assign owner |
| Reminder timing unspecified | Upcoming-deadline notifications required, but lead time, scheduling, retries and deduplication not fixed | Agree operational rules within LMS-12 |
| Profile/avatar and registration-email behavior need alignment | UC1.5 discusses invalid/large avatar files, while DOC-05 provides `avatar_url` without upload semantics. UC1.1 requires confirmation email, whereas DOC-05 says “if” sent | Decide upload versus URL-only scope and confirmation-email behavior |

DOC-03 §5.2 uses “lessons viewed” in a progress description, whereas DOC-01, DOC-04, DOC-05 and DOC-06 explicitly require student completion marks. The latter rule is consistent across the detailed contracts; align the architecture wording through review.

### 4.2 Cross-repository integration gaps

1. **Default backend port mismatch:** Frontend `.env.example` expects Identity on `8080` and LMS on `8081`. Neither backend `src/main/resources/application.yaml` sets `server.port`; without external overrides both use Spring Boot's default port.
2. **Unused clients:** The only source references to `centralIdentityClient` and `lmsClient` are their declarations. Their existence does not demonstrate compatibility.
3. **Browser integration policy absent:** `src/api/http.ts` has no token handling; `vite.config.ts` has no development proxy; no application CORS configuration was found in either backend. Cross-origin authenticated calls remain unverified.
4. **DTO contracts not implemented:** Empty DTOs cannot establish snake_case naming, pagination envelopes, error shapes, validation or response-code compliance.
5. **Token integration absent:** LMS expects signed tokens and key retrieval that Identity does not provide.

### 4.3 Security findings

**Priority: complete authorization before exposing business endpoints.**

- `L/config/SecurityConfig.java::securityFilterChain()` enforces authentication only. It does not consult `LmsUserAccess`, reject locally suspended users, resolve local roles, or check membership/ownership.
- Token tests reject missing credentials and the malformed string `not-a-valid-jwt`. They do not establish valid-token acceptance, expiry/wrong-signature rejection, local suspension, RBAC or object authorization.
- Identity has no password hashing, active-account check, login lockout, redirect validation or secure reset-token lifecycle implementation.
- Initial-admin race prevention and last-active-admin protection are absent.
- Upload validation and protected file delivery are absent; these must accompany content/submission implementation.
- `I/exception/GlobalExceptionHandler.java`, `I/exception/ApiErrorResponse.java`, and `L/exception/GlobalExceptionHandler.java` are empty. The specified safe, consistent API error response is not implemented.

These are implementation gaps. Business endpoints are absent, so this audit does not claim an exploit against an existing class or submission endpoint was demonstrated.

**Correctly outside MVP:** refresh tokens, server-side logout, token blacklisting and immediate JWT revocation. Their absence follows the supplied design.

## 5. Duplicate or unnecessary issue recommendations

### 5.1 Preserve completed work

| Completed issue | Supporting merged PR | Recommendation |
|---|---|---|
| Frontend #1 — FE-0 | [Frontend PR #2](https://github.com/henpham1412/co3103-g11-frontend/pull/2) | Preserve routing, Axios clients and CI; authentication belongs in FE-1 |
| Identity #2 — CI-0 | [Identity PR #4](https://github.com/henpham1412/co3103-g11-central-identity/pull/4) | Preserve V1 schema/configuration; extend incrementally |
| Identity #5 — CI-1 | [Identity PR #6](https://github.com/henpham1412/co3103-g11-central-identity/pull/6) | CI exists; no replacement CI issue needed |
| LMS #2 — LMS-0 | [LMS PR #3](https://github.com/henpham1412/co3103-g11-lms-instance/pull/3) | Preserve JWT foundation; PR explicitly leaves local-role checks and real-token integration pending |
| LMS #4 — LMS-1 | [LMS PR #5](https://github.com/henpham1412/co3103-g11-lms-instance/pull/5) | CI and migration smoke test exist |

Accessible histories contain one Frontend PR, four Identity PRs and three LMS PRs, all merged. Earlier initialization/name-fix PRs do not establish completed business functionality.

The audit inspected 13 Frontend issues, four Identity issues and 21 LMS issues, including closed setup issues. Search results alone omitted one closed LMS issue; the all-state issue listing was used for the completed-work review.

### 5.2 Open backlog assessment

**No open business-feature issue was found wholly redundant.** Its intended functionality remains required.

Recommended scope boundaries:

- **LMS-2 versus LMS-0:** Extend JWT configuration; do not recreate the baseline.
- **FE-1 versus FE-0:** Reuse API clients/URLs; focus on authentication and protected routing.
- **LMS-4 versus LMS-5/LMS-6:** Assign atomic Activity-plus-assessment creation explicitly; avoid conflicting implementations.
- **LMS-2 versus LMS-13:** Runtime access resolution/bootstrap in LMS-2; subsequent administration and last-admin protections in LMS-13.
- **FE-3 versus FE-8:** Share one “My Classes”/progress integration rather than duplicate class views.
- **OPS-1 versus REL-1:** OPS-1 establishes/verifies the environment; REL-1 packages verified setup and demo instructions.
- **Feature tests versus QA-1/QA-2:** Feature issues own their tests; QA issues cover cross-module journeys/security regression.
- **Administrative class management:** UC7.2 is not explicit in LMS-13's user-management criteria or FE-12's listed criteria; assign deliberately.
- **Adaptive evaluation:** Include controlled-profile versus fixed-baseline evaluation in LMS-9/QA scope.
- **Notifications:** Read/unread behavior is required despite FE-11's “if supported” wording; include retention cleanup explicitly.

Unnecessary additions would include a separate `Enrollment` entity, duplicate `Skill` entity, stored basic-report entity, standalone Adaptive Learning dashboard, or public initial-admin bootstrap endpoint.

No issues were created, edited or closed during the audit.

## 6. Prioritized follow-up actions

| Priority | Action | Existing issue ownership |
|---|---|---|
| P0 | Resolve JWT/key distribution, provisioning/role discovery, password/lockout policy and conflicting status terminology | CI-2, LMS-2, relevant design review |
| P0 | Implement registration/login/signing and local access/RBAC/bootstrap | Identity #7; LMS #7 |
| P0 | Align service ports, safe configuration, browser integration and isolated test setup | OPS-1 #21; FE-1 #3 |
| P1 | Deliver authenticated slice: login → local role → tutor class creation → student membership | FE-1, LMS-2, LMS-3, FE-3/FE-4 |
| P1 | Curriculum, visibility, authorized uploads and retention rules | LMS-4; FE-5 |
| P1 | Quizzes, assignments and progress with scoped authorization/concurrency | LMS-5–7; FE-6–8 |
| P1 | Recovery/profile flows including required email delivery | CI-3; FE-2 |
| P2 | Topic management, mastery, recommendation and controlled evaluation | LMS-8/9; FE-9 |
| P2 | Discussions, notifications, moderation, administration and reports | LMS-10–15; FE-10–12 |
| Release gate | Core journey, authorization regression and clean-install demo | QA-1/2; OPS-1; REL-1 |

Independent screens, domain logic, migrations and test cases can proceed against agreed contracts. Only the affected integration or acceptance check should wait on a specific unresolved dependency.

## 7. Tests executed, results and blockers

### 7.1 Checks executed during the audit

Checks used snapshots at the primary GitHub SHAs, not the older local `work` branches. Paths below record the audit environment and are not deployment requirements.

| ID | Repository | Command / check | Actual result |
|---|---|---|---|
| T1 | Frontend | `npm ci --cache /workspace/work/npm-cache --no-audit --no-fund` | Passed: 255 packages installed |
| T2 | Frontend | `npm run lint` | Passed: no ESLint errors reported |
| T3 | Frontend | `npm run build` | Passed: TypeScript/build completed; Vite emitted a chunk-size warning |
| T4 | Identity | Maven 3.9.12 `verify`, JDK 25, existing dependency cache | Blocked before tests: Flyway artifacts absent offline; online attempts failed resolving Maven Central |
| T5 | LMS | Same Maven verification procedure | Blocked before tests: same dependency-resolution failure |
| — | Original checkouts | `git status --porcelain` | All three remained clean |
| — | Snapshots | Git blob hash comparison | All 94 files matched |

Environment: Node.js 24.19.0, npm 11.9.0, configured JDK 25.0.4.1 and Maven 3.9.12. The shell default Java was 21; verification explicitly selected JDK 25.

The backend command, run in each backend snapshot, was:

```bash
JAVA_HOME=/workspace/.tools/jdk-25 \
/workspace/.tools/apache-maven-3.9.12/bin/mvn \
  --batch-mode --no-transfer-progress \
  -Dmaven.repo.local=/workspace/.cache/maven/repository \
  verify
```

Offline and explicit-proxy retries were also attempted. Unresolved dependencies included:

- `org.springframework.boot:spring-boot-starter-flyway:4.1.1`
- `org.flywaydb:flyway-database-postgresql:12.4.0`

Online attempts reported temporary name-resolution failure for Maven Central. No backend JUnit test executed locally during the audit. These results do not establish a source/build defect. Database execution was not reached; no isolated PostgreSQL test instance was established.

Frontend build completed with an approximately 625.72 kB minified JavaScript chunk and Vite's warning for chunks above 500 kB. The warning did not fail the build.

### 7.2 T6 — Existing GitHub execution evidence

GitHub Actions and job steps report success at the exact audited commits:

| Repository | Execution | Observed result |
|---|---|---|
| Frontend | [Run 39806049680](https://github.com/henpham1412/co3103-g11-frontend/actions/runs/39806049680) | Install, lint and build steps succeeded |
| Identity | [Run 39816497725](https://github.com/henpham1412/co3103-g11-central-identity/actions/runs/39816497725) | PostgreSQL setup and Maven build/test step succeeded |
| LMS | [Run 39818860485](https://github.com/henpham1412/co3103-g11-lms-instance/actions/runs/39818860485) | PostgreSQL setup and Maven build/test step succeeded |

Both backend `.github/workflows/ci.yml` files run:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

This is observed remote execution evidence, separate from fresh local verification. PR descriptions reporting earlier local passes were treated as historical statements, not as audit-executed tests.

### 7.3 Existing test coverage and limits

**Identity — IT:**

- `contextLoads()`
- `flywayMigrationCreatesExpectedTables()`

**LMS — LT:**

- `contextLoads()`
- `protectedEndpointsRequireAuthentication()`
- `protectedEndpointsRejectInvalidBearerToken()`
- `flywayMigrationCreatesLmsUserAccessTable()`

LMS rejection tests call `/api/v1/classes`, but no mapped class controller exists. A security filter can reject a request before controller lookup: 401 assertions do not prove the class API exists.

No Frontend behavioral test script or feature test suite was found. No existing backend tests verify the business requirements listed as Missing.

**Review disposition:** Keep completed setup issues closed and proceed with the existing feature backlog, subject to identified contract clarifications. MVP completion and end-to-end readiness are not established by this audit.
