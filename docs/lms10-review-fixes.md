# LMS-10 review fixes

## Migration allocation

The integrated main is `09fbab12425777eee5007b15ac412723d27f5f43`, with V1–V14 and
V17–V20. Discussion is V21; its SQL bytes and Flyway checksum (`-1362377202`) are
unchanged. Main's migration files and checksum pins are preserved. Unmerged LMS-11
does not reserve a migration version.

Before allocation, isolated copies of the two accessible PostgreSQL volumes were
inspected. None of their 18 LMS histories had a Discussion migration or table.
The persistent local `g11-postgres` database `lms_instance_dev` had V1–V13.
The other LMS databases were phase/review/test verification databases. Original
containers and volumes were not started or modified. The prior review's Discussion
V18 existed only in a disposable database, whose container was removed. External
deployments not connected to this environment cannot be verified here; confirm
their history before deployment. No Flyway repair/reset/clean was used.

## Conversation retrieval

DOC-01 UC6.2 and DOC-04 specify threaded replies; DOC-05 §3.3 supplies the detail
endpoint but does not specify a reply response shape or reply ordering. FE-10 PR23
currently reads the flat post fields and explicitly reports missing read-back.

`GET /api/v1/discussions/{discussionId}?page=1&size=20` keeps all existing top-level
post fields and adds `replies`, using the existing PageResponse shape:

```json
{
  "discussion_id": "root UUID",
  "class_id": "class UUID",
  "lesson_id": null,
  "author_id": "author UUID",
  "parent_id": null,
  "title": "Thread",
  "content": "Root body",
  "created_at": "timestamp",
  "updated_at": "timestamp",
  "replies": {
    "items": [],
    "page": 1,
    "size": 20,
    "total_items": 0,
    "total_pages": 0
  }
}
```

Each item is an unchanged DiscussionResponse with `parent_id`. This is a flat
page of **all descendants**, not only direct children. Fetch subsequent pages to
reconstruct the complete hierarchy. A reply detail returns that reply and its
descendants. Existing list/create/PATCH responses remain unchanged.

Replies are ordered oldest first by `created_at ASC, discussion_id ASC`; this is
an explicit technical default where the documents are silent. Thread-list recent
activity ordering remains unchanged. Page and size are one-based, default 1/20,
with size limited to 100. Empty/out-of-range pages retain accurate totals.

Two set-based recursive queries count and page descendants under one repeatable
read snapshot. Every hop enforces the original Class and nullable Lesson context.
UNION prevents repeated traversal of a corrupt cycle. There are no recursive Java
calls or per-reply queries; response size is bounded, though traversal work still
scales with thread size. Existing participant checks run before retrieval.

FE-10 is not changed here. Its current code remains compatible but must consume
`replies` and request remaining pages to remove its write-only fallback.

## Concurrent PATCH

PATCH obtains the immutable Class ID through a scalar query, acquires the existing
Class write lock, rechecks Discussion existence, and refreshes the managed entity
before applying the allowlisted fields. A caller's preloaded entity is refreshed
too. A Discussion deleted while waiting produces DISCUSSION_NOT_FOUND. Class-first
mutation ordering and archived-class checks are preserved; no version column or
additional schema change is needed.

Regression tests synchronize two transactions with a latch and observation of an
actual PostgreSQL lock wait. They cover title/content preservation and concurrent
deletion, both with and without an entity preloaded in the calling transaction.

## Verification

JDK 25.0.5.0.3 and disposable PostgreSQL 18.6, with explicit database variables:

```bash
source /workspace/.setup/java-env.sh
DB_URL=jdbc:postgresql://localhost:55439/lms10_fixes_final_full \
DB_USERNAME=postgres DB_PASSWORD='' \
bash mvnw -s /workspace/.setup/maven-settings.xml \
  -Dmaven.repo.local=/workspace/.setup/m2-lms \
  --batch-mode --no-transfer-progress clean verify
```

Result: **1,908 tests, zero failures/errors/skips; BUILD SUCCESS**. This includes
24 Discussion tests, both fresh/main-upgrade Discussion migration cases, four
migration-policy tests, and the existing LMS-3/4/6/7/8/9/13 regressions. PostgreSQL
logs also pass the CI diagnostic-privacy check: required constraint-failure markers
are present, while raw failing-row details and profile canaries are absent.

Restoring only the original load-before-lock PATCH sequence made both variants of
the new concurrent-update test fail with `expected: Committed new title; actual:
Original`. The fixed source was restored before final verification. An earlier
full run exposed two obsolete migration-execution counts in MainV13MigrationIntegrationTest;
these now assert the exact counts including V21. No assertion or test was removed.
