# LMS-11 review fixes and LMS-15 handoff

## Baseline and migration

Integrated main `ee6d3dd0372d98ddc90ff132b5b18747990253de` into the existing
LMS-11 branch by normal merge. Preserve LMS-10 conversation pagination, Class-lock
PATCH refresh and tests, LMS-6 Assignment configuration, and LMS-13 access/audit
and error contracts. Constructor conflicts combine the required dependencies.

Current main has V1–V14 and V17–V21. ContentReport is allocated V22, calculated
against main only; unmerged branches reserve no versions. SQL bytes and checksum
`420574687` are unchanged. All 19 merged main migrations and checksum pins remain.
Fresh isolated copies of the two accessible PostgreSQL volumes contained 18 LMS
histories, none with ContentReport migration/table. Persistent local
`lms_instance_dev` had V1–V13. Phase/review verification databases are distinct
from that persistent development database. Original containers/volumes were not
started or modified. External deployments are not accessible: confirm their
history before deployment. No Flyway clean/repair/reset/history rewriting is used.

## Moderation content inspection

DOC-01 UC7.3 and DOC-05 §5.3 require the Administrator to inspect reported content.
Existing `GET /api/v1/admin/content-reports/{id}` keeps its fields and adds an
admin-only `target` object when the row exists. Lists and review PATCH responses
retain their existing metadata shape. The target includes the reported title/body,
Class/Lesson context, Discussion author/parent or Material type/visibility, and
Class status. Hidden and archived content can be inspected for moderation without
changing ordinary Student/Tutor access rules.

`target_available` continues to mean the target row exists. Missing targets retain
the report, return false, and omit `target`. Stored-file availability is separate:
`target.file_available` is true/false; missing files produce no URL. Available stored
files link to `GET /api/v1/admin/content-reports/{id}/target/file`, which authorizes
the active local Administrator and resolves only that report's Material. It reuses
canonical storage-key validation, file-type handling and download behavior.
Student/Tutor, suspended and anonymous access remain denied. No physical path is
serialized. Valid external HTTP(S) links without embedded credentials are returned
without server-side fetching; their availability is unknown (null). Unsupported
or physical stored paths are not exposed.

## Locks and historical reports

Report creation resolves the immutable owning Class ID by projection, locks that
Class, refreshes the relevant target/context, and rechecks canonical read access
before insertion in the same transaction. Both normal target-deletion paths hold
the same Class lock and check unresolved reports before deletion. The reporting
operation preserves existing archived read/report behavior; it does not introduce
an archived-class write restriction intended for learning participation.

Reviews lock and refresh the ContentReport row before testing its current state.
Review never acquires a Class lock. Creation/deletion acquire Class first; there
is no review Report→Class lock path to form a lock-order cycle. Final outcomes
are monotonic and never become unresolved again. Reports remain after legitimate
target deletion. Lifetime reporter/type/target uniqueness is unchanged.

## Transition interpretation

DOC-04 defines PENDING, REVIEWED, RESOLVED and DISMISSED. DOC-05 §5.3 records review
identity/time and documents conflicts; DOC-01 UC7.3 requires review and traceability.
The documents do not fully define the graph. Under the implementation authorization,
the narrow coherent interpretation is:

| Current | Allowed next states |
| --- | --- |
| PENDING | REVIEWED, RESOLVED, DISMISSED |
| REVIEWED | RESOLVED, DISMISSED |
| RESOLVED | none |
| DISMISSED | none |

PENDING is not a review outcome (400 INVALID_REPORT_STATUS). Repeated REVIEWED and
attempted changes of terminal reports return 409 REPORT_ALREADY_UPDATED. Unresolved
statuses remain PENDING/REVIEWED. `reviewed_by`/`reviewed_at` identify the latest
successful moderation transition; the audit retains every successful transition.
Concurrent terminal decisions serialize; the loser receives the documented conflict.

## Minimal audit extension and LMS-15 #20

DOC-01 UC7.3, DOC-04 AuditLog examples and DOC-05 §5.3 require an actual event.
LMS-15 #20 owns broader event coverage and audit retrieval. This change adds only
a factory to the existing AuditLog entity, preserving LMS-13's existing constructor,
table, repository and user-access behavior. No audit schema change is required.

* action: `REPORTED_CONTENT_REVIEWED` (DOC-04 example)
* entity_type: `ContentReport`; entity_id: report UUID
* actor_id: authenticated local Administrator UUID
* created_at: event time
* details: `{"before":{"status":"PENDING"},"after":{"status":"RESOLVED"}}`

Only enum states are interpolated; no target content, report descriptions, profile
data or credentials enter details. Report state and audit event are flushed in the
same transaction. Rejected transitions create no event; either write failing rolls
back both. LMS-15 should reuse this event when implementing retrieval and must not
record a second duplicate event for these transitions. Issue #20 is not modified;
this document and PR description record the shared-framework handoff.

## Pagination and query budget

Admin list ordering is `created_at DESC, report_id DESC`. Target availability uses
up to two batched ID projections, one per target type, rather than per-item queries.
The normal page budget is at most four statements: content, count, Discussion IDs,
Material IDs. Count queries can be skipped by Spring Data for a partial last page.

## Regression coverage

Real HTTP/PostgreSQL tests cover both target inspections, file download/missing and
physical-path safety, hidden/archived access, all 16 status pairs, event attribution
and privacy, audit-insert failure and transaction rollback, both report/delete lock
orderings for both target types, competing Administrators (including preloaded
entities), equal-timestamp traversal of all pages, and the bounded query budget.
Migration tests cover fresh installation and V21→V22, history/data preservation,
checksum, constraints and indexes. Existing LMS-10/13 and other regressions remain.

## Executed verification

JDK 25.0.5.0.3, PostgreSQL 18.6, and ffprobe available. Focused verification of
ContentReport, its migration, Discussion, Chapter and migration policy passed
107 tests with no failures/errors/skips. The final full suite, including three
additional privacy/rollback/archive cases, passed **1,965 tests, zero failures,
errors or skips**:

```bash
source /workspace/.setup/java-env.sh
DB_URL=jdbc:postgresql://localhost:55441/lms11_fixes_full \
DB_USERNAME=postgres DB_PASSWORD='' \
bash mvnw -s /workspace/.setup/maven-settings.xml \
  -Dmaven.repo.local=/workspace/.setup/m2-lms \
  --batch-mode --no-transfer-progress clean verify
```

The 48 ContentReport cases include four synchronized report/delete orderings,
competing review decisions, all 16 status pairs, and repeated equal-time page
traversal. The 20-item query-count regression enforces at most four statements.
Database logs pass the CI privacy check: required constraint-failure evidence is
present; profile canaries and raw failing-row details are absent. A missing-file
test caught a nested-transaction rollback issue during development; inspection
now uses an optional file result instead of swallowing a transactional exception.
