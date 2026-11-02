# Presentation lifecycle scenarios

This opt-in recipe extends PresentationSeedMaintenance and PresentationSeedCommand.
It consumes the existing replacement-listings-v1.json: exactly the same 40 listing
UUIDs, donor ownership, titles, descriptions, categories, quantities, units, pickup
areas and 36 Cloudinary HTTPS mappings. There is no second dataset or startup seeder.
The JSON is unchanged. Historical scenario deadlines intentionally override its
original future-only deadline recipe for selected IDs in a fresh disposable install.

## Reachable states and initial counts

| Entity | State | Count |
|---|---|---:|
| Listing | AVAILABLE | 32 |
| Listing | RESERVED | 1 |
| Listing | COLLECTED | 1 |
| Listing | EXPIRED | 4 |
| Listing | DELETED | 2 |
| Listing | REVERSED | 0 |
| Request | PENDING | 1 |
| Request | APPROVED | 2 |
| Request | DECLINED | 3 |
| Request | CANCELLED | 1 |
| Request | REVERSED | 1 |
| Request | EXPIRED | 3 |
| Handover | AWAITING_COLLECTION | 1 |
| Handover | COLLECTED | 1 |
| Handover | REVERSED | 1 |
| Handover | EXPIRED | 1 |

Totals: 40 listings, 11 requests, 4 handovers, 6 fixture identities and 70 activity
records (including one explicitly labelled installation manifest). No listing is
forced to REVERSED: that status remains schema-supported historical data but is not
produced by current application reversal. Handover CANCELLED and donor approval
cancellation are not implemented. Request/handover reversal and listing reopening
follow Esther's behavior. DELETED is reachable only by archival of an eligible
AVAILABLE or already-EXPIRED listing, not of an active reservation or collection.

## Stable listing/request/handover map

All IDs are actual UUIDs. Request assignments: listing 2/3/4/7/11 use recipient1;
listing 5/9 use recipient2; listing 6/10 use recipient3. Listing 4 competitors use
recipient2 and recipient3. Recipient IDs retain DemoSeed.id("recipientN").

| # | Listing UUID | Scenario | Request UUID(s) | Handover UUID |
|---:|---|---|---|---|
| 1 | f2080471-bd27-3134-81f4-0b25fc5877f4 | AVAILABLE, no requests | - | - |
| 2 | 53defde4-c6b0-36e2-8b34-8454b06128ed | AVAILABLE, pending request | 35883d78-2ce7-3c01-9b17-39a0d5b3e453 | - |
| 3 | ac1c97bd-6dd8-3693-8c77-ed7f3482c204 | AVAILABLE, donor-declined request | 25147b22-fb10-3ae3-95ef-012c1d5d1feb | - |
| 4 | fec5c249-fbfc-31f8-8483-0e2cac75fcc6 | RESERVED, approved; two auto-declined competitors | cc309130-840b-3bfe-9c07-6938d86b244d; competitors: 81616991-2f4d-3989-8273-89538c6e4283; d8b79eb6-bcbc-3087-9791-2a106b09b55b | 45610517-9556-33d2-bbc4-02ff867027d9 |
| 5 | 691c1638-d91d-37b5-97a8-9511519fd2e5 | COLLECTED | 570c7a76-7168-3577-b2be-bef47bd3b3dc | 8ee55821-acbc-3e47-8b73-387377f0c1ac |
| 6 | 52a2ab53-3a1a-3b76-8acb-9d2e383f3bfb | AVAILABLE after completed-handover reversal | dea8cc64-567d-3867-bad5-3fd12f5911c6 | 552e1bca-b117-3aad-aa74-f00c8092b323 |
| 7 | cde473ce-248b-3bff-aef8-35c98731a3f7 | AVAILABLE, recipient-cancelled request | 7ca1ab71-6c32-3f04-8b06-692f2b6a1d73 | - |
| 8 | ee787267-e899-3eaf-9148-2ff16ac63c42 | EXPIRED, no requests | - | - |
| 9 | c2038cdc-fcf7-361b-bcd8-ba89510c2452 | EXPIRED while PENDING (A) | 78def781-7fed-35d8-9495-eaa06e75f0bc | - |
| 10 | dc1adcc9-a15d-368e-b4f1-0f1ceb5f820a | EXPIRED while PENDING (B) | c1ce0529-0e73-38e6-b81f-0baa6e9411a3 | - |
| 11 | b9faa5ac-099a-3cdc-a855-956dcad12850 | EXPIRED reservation and awaiting handover | 907eb5a4-14a6-385b-b291-9ca18f3b2f8f | 8b9ac73a-befa-3d4a-840e-fae09604dcab |
| 12 | cd9b12e7-a476-3e2d-a52e-268f9a2854c9 | DELETED from AVAILABLE | - | - |
| 13 | e8ff8738-2264-328a-be70-58e06674934e | DELETED after expiration, metadata retained | - | - |
| 14 | b47254f4-ea6a-3107-aec5-cb05d93ab66d | AVAILABLE, no requests | - | - |
| 15 | fb3d4cc8-7a65-3fb1-b348-146415de4ea4 | AVAILABLE, no requests | - | - |
| 16 | 103ae7e1-ef6c-34f4-8243-c8cd52a0ab67 | AVAILABLE, no requests | - | - |
| 17 | 30ba3016-94d3-337b-b103-17bc53960c4f | AVAILABLE, no requests | - | - |
| 18 | 70213992-acf7-3876-b795-e9736a68e8f3 | AVAILABLE, no requests | - | - |
| 19 | fe939f6b-829c-3a01-8c8d-2f5ab4d1a47a | AVAILABLE, no requests | - | - |
| 20 | a981a609-1429-3a2d-8fa3-f5b2e01c5676 | AVAILABLE, no requests | - | - |
| 21 | 79c00bbc-5f04-3ec7-ad31-03f8d6a1da1c | AVAILABLE, no requests | - | - |
| 22 | 2bea587f-afe7-3cb4-ae12-9b4bb8b4564c | AVAILABLE, no requests | - | - |
| 23 | 45189ad1-62c3-3b28-8ee4-918e4aad6d78 | AVAILABLE, no requests | - | - |
| 24 | 1914942b-5555-3566-8941-1ee0f947c0ee | AVAILABLE, no requests | - | - |
| 25 | dc0cc75e-d61e-3c43-a138-94b13f8ea088 | AVAILABLE, no requests | - | - |
| 26 | df8777eb-1beb-35ae-be04-0f6988f41320 | AVAILABLE, no requests | - | - |
| 27 | a529250a-682f-3c6a-95d0-399c7d025259 | AVAILABLE, no requests | - | - |
| 28 | 7f7eec31-8bc2-3831-afaf-31c8af3af1f2 | AVAILABLE, no requests | - | - |
| 29 | e8cb7d14-78ea-3c31-bb51-63e20a8aece6 | AVAILABLE, no requests | - | - |
| 30 | 9c354266-92d8-3f0f-99b8-f832deb77c2d | AVAILABLE, no requests | - | - |
| 31 | e76366da-9a64-3bc0-8eb1-dc57290af150 | AVAILABLE, no requests | - | - |
| 32 | 0cfb281b-cea5-3fa2-8138-bf852908fcda | AVAILABLE, no requests | - | - |
| 33 | 52b883a7-df48-3327-971f-7647d6421ca3 | AVAILABLE, no requests | - | - |
| 34 | ba219d21-e87c-3f16-9e8d-1ba66f9aa191 | AVAILABLE, no requests | - | - |
| 35 | 78634a94-81d9-33b6-bc0d-23fcde9195a9 | AVAILABLE, no requests | - | - |
| 36 | dcd2bf57-7544-3208-95fe-2a152f2c26ba | AVAILABLE, no requests | - | - |
| 37 | 8289c85b-5575-3206-8f66-26b92d9c1d79 | AVAILABLE, no requests | - | - |
| 38 | 97539e4c-6bea-31f0-b3aa-897d5a1fc5c4 | AVAILABLE, no requests | - | - |
| 39 | 1e7a1c97-c47b-330e-b349-8deab229e968 | AVAILABLE, no requests | - | - |
| 40 | 428d09c7-02ae-3b9d-9f76-936022189c20 | AVAILABLE, no requests | - | - |

Activity IDs use the existing DemoSeed.id function with key
presentation-lifecycle-v1/activity/N/STEP. STEP is listing-created,
request-created-primary (or request-created-competitor-2/3), decision-primary
(or decision-competitor-2/3), collect, reverse, expire or archive as applicable.
The manifest ID uses key presentation-lifecycle-v1/manifest.

## Chronology, provenance and fairness

A is the persisted installation anchor sampled once from PostgreSQL after acquiring
installation locks. Identity creation is A-5 days; listing creation is A-4 days.
Current request fixtures are created at A-2 hours; their approval fixtures are at A.
Standalone decline and recipient cancellation are at A-1 hour. Current listing
deadlines use the unchanged dataset's max(A+offset, original floor) recipe.

Listing 9 (c2038cdc-fcf7-361b-bcd8-ba89510c2452) and listing 10 (dc1adcc9-a15d-368e-b4f1-0f1ceb5f820a) are the two
separate expired-while-pending cases. Requests 78def781-7fed-35d8-9495-eaa06e75f0bc and c1ce0529-0e73-38e6-b81f-0baa6e9411a3 are inserted
PENDING at A-71 hours. Both collection deadlines are A-68 hours. ExpirationOperations
actually executes later in the atomic installation: request/listing expired_at and
the LISTING_EXPIRED activity time are the same post-lock PostgreSQL processing time
E, with E >= deadline. The activity details retain previous_status=PENDING and the
changed request ID. Their historical REQUEST_CREATED events are explicitly labelled
HISTORICAL_FIXTURE_NOT_COMMAND_EXECUTION; they are not claimed as delivered API calls.

Listing 11's request is created at A-47 hours, approved at A-46 hours, and due at
A-45 hours. Its actual expiration operation preserves the approval decided_at and
sets the listing, unfulfilled request and awaiting handover EXPIRED together. The
historical approval is recipient1's first approval on that distinct Lagos date.

Listing 13's deadline is A-24 hours. Expiration is actually executed before the real
listing deletion operation; expired_at/reason survive DELETED. updated_at and archive
activity record the later archival processing time, and archive details retain the
EXPIRED before-image. Listing 12 is actually archived from AVAILABLE.

Listings 5 and 6 are collected through HandoverOperations; listing 6 is then actually
reversed. completed_at is retained, reversed_at is later, request becomes REVERSED
and decided_at records reversal time, listing reopens as AVAILABLE. The original
approval fixture timestamp remains in its labelled REQUEST_APPROVED event. No deadline
is refreshed. Reversal of an overdue collected listing would still reopen it as main
does; a later guarded command/scheduler pass would expire it without rewriting the
reversed handover (covered by the existing expiration tests).

Why historical insertion is necessary: ReservationOperations uses REQUIRES_NEW.
Calling it inside a new-install transaction cannot see the uncommitted identities
and listings and would break atomic rollback. Its request/handover/activity UUIDs
also are random, and its live deadline guards cannot create historical pending
requests before an already-past deadline. Therefore stable pending/approved/declined/
cancelled fixtures are inserted in this one fresh-install transaction. Their event
provenance explicitly states that these are fixtures, not executed commands.
Listing 4's competitor declines model main's approval auto-decline result; a separate
PostgreSQL test executes real request/approval services and verifies that behavior.

The real DailyDistributionOperations eligibility check runs before each of the
three current approval fixtures, one per recipient. The separate historical approval
is on a different day. No fairness flag, service, security rule, migration or index
is disabled. Tests also prove a served recipient is rejected while recipient3 is
waiting, a first-round approval for recipient3 succeeds, competitors auto-decline,
and a second APPROVED reservation for one listing violates the existing unique index.

For actual collection/reversal/expiration/archive events only, the installation
assigns a deterministic UUID and an event timestamp from the operation's persisted
processing timestamp. The original activity created_at (PostgreSQL transaction-start
time for some services) is retained in details.presentation_fixture. Operation
provenance is DOMAIN_OPERATION_EXECUTED. Existing events are never rewritten on rerun.
Fixture decisions/listing creation use HISTORICAL_FIXTURE_NOT_COMMAND_EXECUTION.
The LISTING_CREATED-typed manifest explicitly says it is installation metadata,
not a fabricated listing command. No activity is claimed as a delivered notification.

Read-only evidence query on a disposable installation:

```sql
SELECT l.id, r.id AS request_id, r.created_at, l.collection_deadline,
       r.status, r.expired_at, l.expired_at AS listing_expired_at,
       a.created_at AS activity_processing_time, a.details::jsonb
FROM listings l JOIN requests r ON r.listing_id=l.id
JOIN activity a ON a.listing_id=l.id AND a.type='LISTING_EXPIRED'
WHERE l.id IN ('c2038cdc-fcf7-361b-bcd8-ba89510c2452', 'dc1adcc9-a15d-368e-b4f1-0f1ceb5f820a')
ORDER BY l.id;
```

The PostgreSQL evidence test also writes target/presentation-lifecycle-evidence.json
with actual disposable-run timestamps and original expiration details. It contains
no credentials. Absolute times differ per installation; the persisted anchor and
processing times are never regenerated by reruns.

## Captured disposable-run expiration evidence

The accepted full verification run recorded the following values. These are local
Testcontainers records, not shared-database or deployment audit evidence. Both
expiration activities retain previous_status=PENDING and the matching request ID.

| Listing UUID | Request UUID | Request created (UTC) | Deadline (UTC) | Expiration processing (UTC) |
|---|---|---|---|---|
| c2038cdc-fcf7-361b-bcd8-ba89510c2452 | 78def781-7fed-35d8-9495-eaa06e75f0bc | 2026-10-30T21:37:37.802974Z | 2026-10-31T00:37:37.802974Z | 2026-11-02T20:37:38.253266Z |
| dc1adcc9-a15d-368e-b4f1-0f1ceb5f820a | c1ce0529-0e73-38e6-b81f-0baa6e9411a3 | 2026-10-30T21:37:37.802974Z | 2026-10-31T00:37:37.802974Z | 2026-11-02T20:37:38.279861Z |

Each expiration activity timestamp equals its request/listing expired_at and is
later than its collection deadline. New installations choose a new anchor; reruns
of an existing installation preserve the stored dates and processing evidence.

## Expected initial dashboard

| Field | Value | Derivation |
|---|---:|---|
| totalUsers / totalDonors / totalRecipients | 6 / 2 / 3 | Two donors, three recipients, one administrator |
| totalListings | 38 | 40 minus two DELETED |
| availableListings | 32 | Future-deadline AVAILABLE, including the reopened reversal |
| reservedListings | 1 | Listing 4 |
| collectedListings | 1 | Listing 5 |
| expiredListings | 4 | Listings 8, 9, 10, 11; archived listing 13 excluded |
| totalRequests / pendingRequests | 11 / 1 | Pending request on listing 2 |
| completedHandovers | 1 | Only listing 5 remains COLLECTED |
| estimatedPortionsSaved | 18 | Listing 5's 18 PORTIONS; reversed listing 6's 16 excluded |

The dashboard assertions go through the actual admin HTTP endpoint. Counts are
initial-install expectations, not promises to reset subsequent user progress.

## Fresh disposable provisioning (PowerShell, JDK 25, Maven, Docker)

These commands are instructions only for a new local disposable container. They do
not read .env, connect to Neon or use the existing backup/apply target. The password
below is for a newly created throwaway container, not a credential change.

```powershell
docker run --detach --rm --name foodbridge-lifecycle-disposable -p 127.0.0.1:55439:5432 -e POSTGRES_DB=foodbridge_presentation_disposable -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=disposable-only postgres:17-alpine
docker exec foodbridge-lifecycle-disposable pg_isready -U postgres -d foodbridge_presentation_disposable
mvn "-Dmaven.repo.local=C:/Users/HomePC/.m2/repository" compile dependency:build-classpath "-DincludeScope=runtime" "-Dmdep.outputFile=target/presentation-classpath.txt"
$cp = "target/classes;" + (Get-Content target/presentation-classpath.txt -Raw).Trim()
$env:FOODBRIDGE_DISPOSABLE_URL = 'jdbc:postgresql://127.0.0.1:55439/foodbridge_presentation_disposable'
$env:FOODBRIDGE_DISPOSABLE_USER = 'postgres'
$env:FOODBRIDGE_DISPOSABLE_PASSWORD = 'disposable-only'
$seedArgs = @('--enable=true', '--scenarios=lifecycle-v1', '--disposable=true')
java -cp $cp com.tesa.foodbridge.demo.PresentationSeedCommand @seedArgs --mode=migrate
java -cp $cp com.tesa.foodbridge.demo.PresentationSeedCommand @seedArgs --mode=preview --output=target/presentation-lifecycle-plan.json
$review = Get-Content target/presentation-lifecycle-plan.json -Raw | ConvertFrom-Json
if ($review.blockers.Count -ne 0) { throw 'Review lifecycle blockers before applying' }
# Review the plan and target before the next explicitly mutating command.
java -cp $cp com.tesa.foodbridge.demo.PresentationSeedCommand @seedArgs --mode=apply "--approved-preview=$($review.approvalToken)" --output=target/presentation-lifecycle-installed.json
# Same token: no duplicates, date refresh, repairs or progress reset.
java -cp $cp com.tesa.foodbridge.demo.PresentationSeedCommand @seedArgs --mode=apply "--approved-preview=$($review.approvalToken)" --output=target/presentation-lifecycle-rerun.json
# Optional cleanup of this specifically named throwaway container only:
docker stop foodbridge-lifecycle-disposable
```

The CLI first validates an explicit loopback PostgreSQL URL, the exact disposable
database name, no URL credentials/query/fragment, explicit enable/disposable flags
and demo-only profile. It has an explicit schema-only migrate mode; preview never
migrates. Its minimal JDBC service context has no Boot startup, .env import, scheduler
or web/security context. A plain JUnit test runs the actual CLI argument/environment dispatch in-process
against one raw empty PostgreSQL container through migrate, preview, apply and rerun;
main delegates directly to that dispatch. It does not launch additional JVMs.

## Repeatability and existing-installation safeguards

Startup remains preview-only. Existing shared maintenance preview/apply and its
backup-bound approval safeguards remain in place; its preview now includes a static
read-only lifecycle plan. The new path is separately opted in and restricted to the
named loopback disposable database. No .env credentials are loaded on that path.

Fresh application requires all five domain tables to be empty and a current reviewed
preview token. The reviewed JDBC host/port, database/schema/role, schema/constraints, rows, dataset
and plan participate in the token. The actual database and schema must be the
dedicated disposable database and public schema.
Table locks serialize installation and recheck the token before writes. Existing
users are never modified: six disabled fixture identities are inserted only into an
empty disposable database. Donor names/ownership come from the existing dataset.

The stable manifest stores the installation anchor, definition fingerprint, original
approval token and hashed baseline. Reruns validate the baseline and return without
writes. Mutable lifecycle progression and additional operational records are reported
in progress_or_extra_records and retained. Immutable content, ownership, quantities,
images, deadlines, identities, missing seeded rows, changed seed activity or invalid
manifest records are blockers; the command reports them and does not overwrite.
Even when a changed status is valid, it is explicitly reported rather than silently
restored. No rerun refreshes dates or resurrects rows. Tests compare full snapshots,
including users/password placeholders, after read-only preview and repeated apply.

An existing installation without this manifest is always EXISTING_INSTALLATION_READ_ONLY;
even an explicit apply is rejected. Applying scenarios to the 40 IDs already present
in an existing installation is deliberately unsupported. A future such operation
would require a separate explicit existing-installation gate, plan, backup and review;
this milestone does not implement it. Never point the disposable command at a shared
database. No new migration or production lifecycle/security change is required.

## Verification and limits

Focused checks: **43 tests passed, zero failures, errors or skips**.

```powershell
mvn "-Dmaven.repo.local=C:/Users/HomePC/.m2/repository" "-Dspring.test.context.cache.maxSize=1" "-DargLine=-Xmx512m" "-Dtest=PresentationLifecycleCommandPostgresTest,PresentationLifecyclePostgresTest,PresentationSeedPostgresTest,PresentationSeedSafetyTest,ModuleBoundaryTest" test
```

Log: lifecycle-focused-final.log. This includes two actual CLI dispatch checks,
25 lifecycle fixture checks, 11 existing presentation PostgreSQL checks, four
existing safety checks and one module-boundary check. The run reported BUILD SUCCESS;
Surefire also logged a fork-shutdown timeout after System.exit(0). No assertion was
skipped or removed to obtain the pass.

Full verification of the final code: **182 tests passed, zero failures, errors or
skips; BUILD SUCCESS and Maven exit code 0**.

```powershell
mvn "-Dmaven.repo.local=C:/Users/HomePC/.m2/repository" "-Dspring.test.context.cache.maxSize=1" "-DargLine=-Xmx512m" clean verify
```

Log: lifecycle-clean-verify.log. The context-cache limit closes old Spring contexts
whose disposable containers have stopped; the test heap limit bounds local test
memory use. Neither setting changes production lifecycle or security behavior.
The Maven repository override selects the existing local dependency cache.
An earlier full run was deliberately interrupted to add endpoint-bound preview
approval; that interrupted run is not reported as a pass.

All database writes are confined to PostgreSQL Testcontainers. Historical fixture
provenance is not evidence that historical reservation HTTP calls ran; only labelled
real domain operations and independent service tests make that claim. Existing-
installation scenario application is intentionally unsupported and cannot overwrite
the 40 IDs already present in an existing database. Listing REVERSED remains a
schema-supported historical state, not a state produced by current reversal.
No shared-database seeding, load testing, production credential change, push or
deployment is part of this milestone.

## Files and final repository status

Changed tracked files: .gitignore, PresentationSeedCommand.java and
PresentationSeedMaintenance.java. New milestone files: PresentationLifecycleScenarios.java,
PresentationLifecycleCommandPostgresTest.java, PresentationLifecyclePostgresTest.java
and this document. The .gitignore exception makes this requested document visible
without exposing other ignored presentation reports.

Implementation and verification used baseline
44113112898e63b57168e13901c7b01d67fa2a69 on dayo/presentation-seed-recovery.
The milestone commit includes only the implementation, tests, this document and
the necessary .gitignore exception. The four pre-existing untracked reports and
all local logs are excluded and preserved. The stash
a444977e444f49a7403367af0f67dcb2b67d18ef is unchanged. The original dataset and
all applied migrations have no diff. git diff --check and the staged whitespace
check pass. No Neon/shared database, push, deployment or load test was used.
