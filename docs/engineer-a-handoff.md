# Engineer A → Esther: Monday MVP

## Status and scope

Data/rules implementation lives on `dayo/data-rules`. Controllers, security, exception advice, OpenAPI,
CI/CD and deployment remain Esther's responsibility. These paths and DTOs are **proposed**, pending
Esther/Muizz agreement; no agreement or external coordination is implied by this document.
Spring Boot stays at 3.5.7, Java release 25. No added dependencies. Explicit JDBC persistence is used
inside owning modules for conditional writes/row locks; five JPA entity mappings validate the schema.

## Database

- V1 creates `users`, `listings`, `requests`, `handovers`, `activity`, FK indexes, status/value checks,
  positive quantities, unique email/idempotency key/request-handover relationships, decision timestamps.
- V2 creates `one_approved_request_per_listing`, unique on listing_id WHERE status='APPROVED'.
- `ddl-auto=validate`; Flyway owns schema changes. UUIDs originate in Java. All times are timestamptz/Instant.
- Categories: PREPARED_MEALS, PRODUCE, BAKERY, DAIRY, PANTRY, OTHER.
- Units: PORTIONS, KG, LITRES, ITEMS, BOXES.
- Listing states: AVAILABLE, RESERVED, COLLECTED, REVERSED, EXPIRED. Requested is represented by
  PENDING requests on an AVAILABLE listing, rather than an additional listing status.
- Manual SQL: `src/main/resources/db/rollback/V2__manual.sql`, then V1. V1 deletes ALL domain data;
  V2 removes invariant protection. Back up/stop writers first. Flyway Community does not run these.
  History reconciliation is manual; prefer backup restore or disposable database recreation.

## Service contracts and proposed endpoints

Inject `listing.api.ListingOperations` and `reservation.api.ReservationOperations`.
Construct `shared.Actor(UUID, Role)` exclusively from authenticated server-side identity.
Database role verification rejects forged/stale role claims. Never accept Actor from client JSON.

| Endpoint | Contract | Proposed success |
| --- | --- | --- |
| POST /listings | `create(actor, CreateListing)` | 201 ListingView |
| GET /listings?offset=0&limit=20 | `browse(offset,limit)` (limit 1–100) | 200 array of ListingView |
| POST /listings/{listingId}/requests | `request(actor,listingId,idempotencyKey)`; optional Idempotency-Key header, empty body | 201 RequestView; replay can return 200 |
| POST /requests/{requestId}/approve | `approve(actor,requestId)`; empty body | 200 RequestView |
| GET /requests/{requestId} | `get(actor,requestId)` | 200 RequestView |

CreateListing: title, description, category, quantity (decimal, max 2 fraction digits), unit,
nullable imageUrl, pickupArea, collectionDeadline (ISO-8601 instant). ListingView adds id, donorId,
status, createdAt, updatedAt. RequestView: id, listingId, requesterId, status, createdAt, nullable decidedAt.
See Java records for exact types. Do not serialize JPA entities or password hashes.

Authorization: DONOR creates listings; RECIPIENT requests; owning DONOR or verified ADMIN approves.
Request details are visible only to its recipient, listing owner, or admin. Esther must protect
all endpoints with authentication and decide whether listing browsing requires authentication.
No identity/security adapter is implemented here. Admin approval is explicitly permitted by this MVP policy.

`RuleViolation.code()` is HTTP-independent. Required web-adapter mappings:

| Code | HTTP |
| --- | --- |
| LISTING_ALREADY_RESERVED (including ListingAlreadyReserved exception) | 409 |
| REQUEST_NOT_PENDING, IDEMPOTENCY_KEY_REUSED, LISTING_EXPIRED | 409 |
| FORBIDDEN | 403 |
| LISTING_NOT_FOUND, REQUEST_NOT_FOUND | 404 |
| INVALID_LISTING, INVALID_PAGE, INVALID_IDEMPOTENCY_KEY | 400 |

Missing authentication is 401 in Esther's security adapter. Map expected constraint/input failures
without exposing SQL. Unknown integrity errors should be investigated, not mislabeled as reservation conflicts.
Idempotency keys are globally unique, max 200 chars; replay is bound to same recipient and listing.
Replaying returns the existing request even after expiry; it does not create a new request.
Duplicate approval returns 409 (never a second handover). Losing requests stay PENDING for MVP.

Approval owns a REQUIRES_NEW READ_COMMITTED transaction, so adapters must treat it as the entire
unit of work, not compose it into a larger write transaction. Listing lock serializes related writes;
deadline is checked using clock_timestamp after lock acquisition. Conditional PENDING update and
partial unique index protect the write. Handover, listing state, activity and approval commit together.
Constraint translation happens outside the rolled-back transaction.
Cross-module repositories are prohibited by ArchUnit; use only module api packages.

## Verification

Install JDK 25, Maven and Docker with Linux containers, then run `mvn -B verify`.
No Maven wrapper exists. Tests do not skip when Docker is unavailable: fix Docker access to obtain evidence.
Test-only docker-java.properties selects Docker API 1.44 for Docker 29 compatibility. Override with
`mvn -B '-Dapi.version=1.44' verify` if a local docker-java configuration takes precedence.
PostgresRulesTest starts PostgreSQL 17, applies Flyway, validates all JPA mappings and tests simultaneous
approval, expiry including lock waits, authorization, invalid quantity, idempotency, and transaction rollback.
ModuleBoundaryTest enforces module internals; RequestIdFilterTest checks correlation cleanup.

For slide 4, run in PowerShell:

```powershell
mvn -B '-Dtest=ConstraintEvidenceTest,PostgresRulesTest' test 2>&1 | Tee-Object -FilePath docs/concurrency-output.log
```

ConstraintEvidenceTest creates its own disposable PostgreSQL container and **cannot use DB_URL**.
It applies migrations, drops the index only there, synchronizes three transactions after each sees
zero approvals, and records THREE commits. This is an expected failing invariant, asserted as a
counterexample, not a failing JUnit suite. It resets rows, restores the exact index in finally, repeats
the same writes, and asserts ONE commit plus TWO SQLSTATE 23505 rejections. Both phases query stored rows.
This proves database protection is necessary for these uncoordinated writes and enforces at-most-one;
it does not prove authorization/expiry/handover rules. PostgresRulesTest proves service behavior and
atomic state. The service's row lock intentionally masks the race; removing the index from that test
alone is not valid necessity evidence. Never run index-removal SQL on shared local or Render databases.

## Demo fixtures

Explicitly enable both the `demo` profile and `foodbridge.demo.seed-enabled=true`.
The runner is disabled with prod/production profiles. Use a dedicated non-production FoodBridge DB only.
Set DEMO_PASSWORD_HASH to an encoded hash compatible with Esther's PasswordEncoder; coordinate the
demo plaintext password separately. There is no default credential and no credential is logged.

```powershell
$env:DB_URL='jdbc:postgresql://localhost:5433/foodbridge'
$env:DB_USERNAME='foodbridge'
# Set DB_PASSWORD and DEMO_PASSWORD_HASH securely in your local environment.
mvn spring-boot:run '-Dspring-boot.run.profiles=demo' '-Dspring-boot.run.arguments=--foodbridge.demo.seed-enabled=true'
```

Fixtures: donor1/donor2, recipient1/recipient2/recipient3, admin, all at `@demo.foodbridge.invalid`.
Six fictional users (third recipient enables three distinct eligible requests), 12 listings; listing3
has three pending requests and belongs to donor1. Listings11/12 expired; listing9 no image;
listing10 long title; listings5/6/7 reserved/collected/reversed. Deterministic UUIDs from DemoSeed.id
and ON CONFLICT DO NOTHING make reruns idempotent without overwriting demo interactions. Deadlines are
set on first insert; old demo fixtures eventually expire. Recreate a disposable demo DB for a fresh run.
Reversed collection remains reserved historically; reopening/cancellation/handover endpoints are deferred.

## Environment and remaining integration

DB_URL defaults to localhost:5433/foodbridge; DB_USERNAME defaults to foodbridge; DB_PASSWORD is required.
PORT defaults to 8080. Never target PayBridge or Render. Tests override connection properties to containers.
DEMO_PASSWORD_HASH and explicit seed flag/profile are needed only for fixtures. Do not enable fixtures
on production, irrespective of profile naming. X-Request-Id accepts safe 1–100 character IDs; otherwise
a UUID is generated, returned in the response header, included in MDC/Boot ECS JSON logs, and cleared.
Each HTTP dispatch logs method and response status under that ID, without bodies, query strings or credentials.
Async background work needs explicit MDC propagation if introduced later.

There is no expiry scheduler: deadline checks enforce eligibility even if the persisted status still
says AVAILABLE. Collection/reversal transitions are fixtures only until handover endpoints are added.
Seed image URLs use example.invalid intentionally; frontend should show a placeholder for simulated images.

Esther: agree endpoint/DTO contracts, implement security identity adapter/controllers/error advice,
add credential/registration contracts in user.api for auth integration without accessing user internals,
coordinate password encoder/demo credentials, decide public browsing policy, review category/unit enums,
and run CI/deployment review. Muizz should use named error codes and returned request IDs.
No external messages, pushes, merges or shared/production DB mutations were performed by this task.
