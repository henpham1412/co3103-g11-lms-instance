# LMS-3 / LMS-4 merge impact on LMS-5 assessment

Static handoff review of `experimental/assessment-quiz-llm-output` at `5069b7c` (2026-11-05). The LMS-3 merge is `6c64cca`; the LMS-4 merge is `5069b7c`. Assessment source files were not changed by these merges. No source files were edited for this review, and no build or tests were run.

## What the merges brought in

- **LMS-3:** local user identity and role enums, JWT-to-local-role security, class and membership APIs, and migrations through V4. Membership is now mapped to the singular `class_member` table; `LmsUserAccessRepository` now uses `domain.LmsUserAccess` with `LmsRole` and `AccessStatus` enums.
- **LMS-4:** chapter, lesson, and material APIs, local material storage, content visibility enums, and a new V5 migration. `Chapter`, `Lesson`, and `Material` now keep parent IDs as UUID fields rather than JPA parent-object relationships. `Lesson` exposes `chapterId`; `Chapter` exposes `classId`.
- **Security:** all API requests require an active local LMS role. Assessment controllers can continue using the JWT subject, while `QuizConfigurationAccessService` remains responsible for endpoint-specific tutor/student access.

## Blockers to resolve first

1. **Flyway has duplicate versions.** The migration directory contains two V4 files (`V4__create_classes_and_memberships.sql` and `V4__rename_class_members_table_to_class_member.sql`) and two V5 files (`V5__create_learning_content.sql` and `V5__create_learning_content_tables.sql`). Flyway cannot safely choose between migrations with the same version. The V4 rename is also obsolete for a fresh schema because the LMS-3 V4 migration creates `class_member` directly.
2. **The assessment access service no longer matches the merged Java model.** `QuizConfigurationAccessService` imports `entity.LmsUserAccess`, but `LmsUserAccessRepository` returns `domain.LmsUserAccess`. The canonical model uses enum values, so role/access checks must use `LmsRole` and `AccessStatus`, not string comparisons. The old `entity.LmsUserAccess` also remains as a second entity mapped to the same table and should be retired once references are migrated.
3. **The LMS-4 V5 migration omits `activity`.** The existing LMS-5 `V6__create_assessments.sql` references `activity(activity_id)`. The old LMS-5 V5 creates `activity`, but conflicts with the LMS-4 V5 version. The consolidated content migration must include the activity table before V6 runs.

## LMS-5 code that needs adaptation

- **`QuizConfigurationAccessService`:** it traverses `activity.getLesson().getChapter().getClassField()`, but `Lesson.getChapter()` and `Chapter.getClassField()` were removed. Resolve the path using the new scalar IDs and repositories (`lesson.chapterId` → `chapter.classId` → Class), or agree on a shared content-path access function. Compare `Chapter.VisibilityStatus` and lesson visibility as enums; the Activity status remains a string in the current entity.
- **Shared visibility and access checks:** LMS-4’s public `getLesson(...)` checks membership/ownership and chapter/lesson publication, but there is no public activity-path resolver, it does not check Activity publication, and its student path does not check that the Class is `ACTIVE`. It cannot replace all quiz access checks as-is. Keep the quiz-specific Activity check and agree on one reusable function for the Class → Chapter → Lesson portion.
- **Archived-Class writes (F5):** LMS-4 now has `ensureClassEditable(Class)`, but it is private. A public shared guard would let quiz settings and question mutations apply the same archived-Class rule. Its current class lookup is not locked; the previously discussed concurrent archive race remains a separate policy choice.
- **Tests:** update assessment access tests for the domain user enums and flattened content entities. Add coverage for tutor ownership, student membership, active Class, published Chapter/Lesson/Activity, and archived-Class writes once the shared guard contract is agreed.

## LMS-5-owned follow-up

These are the assessment-side changes; they do not ask LMS-3 or LMS-4 to rewrite their modules:

1. Update `QuizConfigurationAccessService` to use the canonical `domain.LmsUserAccess`, compare `LmsRole` / `AccessStatus` enums, resolve the Class → Chapter → Lesson path using the merged IDs, and compare visibility enums. Keep the assessment-side Activity publication check and existing endpoint error behavior.
2. Update assessment access tests and fixtures to construct the flattened Lesson/Chapter model and enum-based user access model. Cover tutor/student access and the published-content path.
3. Keep the assessment controllers and endpoint routes as they are: they already read the JWT subject, and the merged security configuration allows authenticated active local roles through to the service checks.

Migration version consolidation and exposing a shared Chapter/Class access guard are **cross-module dependencies**, not changes LMS-5 should implement independently. Resolve those contracts with the corresponding branch owners, then have LMS-5 call the agreed API.

## Recommended order

1. Coordinate the migration sequence and inspect `flyway_schema_history` before renaming or removing migrations. For a fresh database, use one V4 for class/membership tables, one V5 containing chapter/lesson/activity/material, and keep assessments at V6. If V4/V5 have already been applied elsewhere, preserve their history and use forward migrations instead of rewriting applied scripts.
2. Consolidate the `LmsUserAccess` model and update the assessment access service for the merged enums and flattened hierarchy.
3. Agree on a shared content-path access method and the public archived-Class write guard; then update assessment access and mutation tests.
4. Run compile and focused assessment/content tests, then run Flyway against a fresh PostgreSQL database and check the integrated endpoints with JWT roles.

