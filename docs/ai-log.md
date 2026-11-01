# FoodBridge Backend — AI Assistance Log (Engineer B: Esther Ebagua)

This log records the AI coding tools used during API, access control, contract verification, and integration work on the FoodBridge backend, including prompts/tasks, what was accepted, what was rejected, and verification findings where AI output was checked and corrected.

---

## 1. Tools Used

- **Google Antigravity (Agentic AI Coding Assistant)** — Used for repository inspection, diagnosing frontend/backend integration issues, writing controller/service/repository pagination and filtering queries, updating `contracts/openapi.yaml`, and writing Spring Boot + Testcontainers integration tests.

---

## 2. Tasks, Accepted Output, and Rejected Output

| Date | Task / Prompt Summary | Accepted Output | Rejected Output & Reason |
| :--- | :--- | :--- | :--- |
| 2026-10-29 | Align listing and request controller DTOs with the OpenAPI contract (`foodName`, `imageReference`, `recipientId`, `recipientName`) and bearer token security. | DTO records (`ListingResponse`, `CreateListingRequest`, `FoodRequestResponse`), `BearerTokenFilter`, and `OpenApiContractTest`. | Rejected using Spring Security's default HTTP Basic popup in browser flows; enforced stateless `BearerTokenFilter` with `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)`. |
| 2026-11-01 | Diagnose `403 FORBIDDEN` on `PATCH /api/requests/{requestId}/collected` and enforce role/ownership rules. | Added `UserDirectory.requireRole` check in `HandoverController` and split row lookup from ownership/state checks in `DatabaseHandoverOperations` so missing rows return `404`, non-owners return `403 FORBIDDEN`, and non-collectable states return `409 CONFLICT`. | Rejected weakening ownership checks (e.g., allowing any authenticated `DONOR` to mark any request collected regardless of `listing.donor_id`). |
| 2026-11-01 | Move listing filtering and `X-Total-Count` calculation from Java streams in `ListingController` into PostgreSQL (`ListingRepository`). | Parameterized SQL filtering in `ListingRepository.queryFiltered` with identical filter predicates for `COUNT(*)` and `LIMIT/OFFSET` page queries, returning `X-Total-Count` header. | Rejected fetching a page first and filtering in Java memory (`result.stream().filter(...)`), which broke page sizes and total counts. |
| 2026-11-01 | Implement admin access to all requests (`GET /api/admin/requests`), CORS preflight for Vercel (`https://food-bridge-ruby.vercel.app`), dashboard `expiredListings` & `estimatedPortionsSaved`, and soft-deletion of `EXPIRED` listings. | Dedicated `GET /api/admin/requests` endpoint, explicit CORS configuration exposing `X-Total-Count`, unit-safe `estimatedPortionsSaved` SQL aggregation, and `DELETED` visibility for admins with `deleted`/`deletedAt` fields. | Rejected overloading `GET /api/requests?mine=false` for admin mode; rejected summing incompatible units (`KG`, `LITRES`, `BOXES`) into `estimatedPortionsSaved`; rejected wildcard `*` CORS origins with `allowCredentials(true)`. |

---

## 3. Verification Case Where AI Output Was Checked and Found Wrong

### Case 1: In-Memory Java Stream Filtering After Database Pagination on `GET /api/listings`

- **What the initial AI-assisted implementation claimed:**
  The initial implementation of `ListingController.browse()` called `listings.browse(offset, limit)` (which executed `SELECT ... LIMIT ? OFFSET ?` in PostgreSQL without filters) and then applied `.stream().filter(...)` in Java for `availability`, `category`, `pickupArea`, `keyword`, and `collectionDeadline`. It claimed this satisfied filtered pagination for `GET /api/listings?offset=0&limit=12&availability=AVAILABLE`.
- **How we checked it:**
  We traced the execution path when the database contained mixed listing states (e.g., `RESERVED`, `COLLECTED`, `EXPIRED`, and `AVAILABLE` listings) and wrote an integration test requesting `limit=12&offset=0&availability=AVAILABLE` while also checking for `X-Total-Count`.
- **What was actually true:**
  1. Because `LIMIT 12 OFFSET 0` ran in SQL *before* the Java `.stream().filter(...)`, any non-matching rows in the first 12 database rows were discarded in memory, returning fewer than 12 items on page 1 even when 15+ matching `AVAILABLE` rows existed in the table.
  2. `X-Total-Count` was completely missing from the response headers, and computing `.size()` on the filtered Java list would only report the current page length rather than the total matching dataset count.
  3. `availability=EXPIRED` failed to return listings whose `status` was still `'AVAILABLE'` in the database after their `collection_deadline` had passed before the 60-second `@Scheduled` job ran.
- **What we did instead:**
  We replaced the in-memory Java stream filtering with database-side filtering in `ListingRepository.queryFiltered()`, using a single captured timestamp (`Instant.now()`) and identical SQL `WHERE` predicates for both the `SELECT COUNT(*)` query and the `SELECT ... ORDER BY l.created_at DESC, l.id LIMIT ? OFFSET ?` data query, and exposed the `X-Total-Count` header in `ListingController` and `SecurityConfig`.

---

### Case 2: Ambiguous SQL `WHERE` Clause Masking State Conflicts as `FORBIDDEN` in `DatabaseHandoverOperations`

- **What the initial code claimed:**
  `DatabaseHandoverOperations.completeByDonorOrAdmin` combined lookup, state checks (`r.status = 'APPROVED' AND h.status = 'AWAITING_COLLECTION'`), and ownership (`? = true OR l.donor_id = ?`) in a single `queryForObject` call, catching `EmptyResultDataAccessException` and throwing `RuleViolation("HANDOVER_NOT_FOUND_OR_FORBIDDEN")`. Meanwhile, `ApiExceptionHandler` did not have a case for `"HANDOVER_NOT_FOUND_OR_FORBIDDEN"`, causing it to fall through to the `default` branch (`400 BAD_REQUEST`) instead of `403 FORBIDDEN` or `409 CONFLICT`.
- **How we checked it:**
  We inspected `HandoverController`, `DatabaseHandoverOperations`, and `ApiExceptionHandler` line by line and wrote integration tests in `PostgresRulesTest.markAsCollectedAuthorizationAndStateTransitions` for an unrelated donor (`donor2`), a recipient (`recipient1`), the owning donor (`donor1`), an administrator (`admin1`), and a repeat call on an already-collected request.
- **What was actually true:**
  1. An unrelated donor calling `PATCH /api/requests/{id}/collected` triggered `HANDOVER_NOT_FOUND_OR_FORBIDDEN`, which `ApiExceptionHandler` mapped to `400 BAD_REQUEST` instead of `403 FORBIDDEN`.
  2. Conversely, if the owning donor called `/collected` twice (or when the handover was no longer `AWAITING_COLLECTION`), the same `EmptyResultDataAccessException` was thrown, making it impossible to distinguish ownership refusal (`403 FORBIDDEN`), missing request (`404 NOT_FOUND`), and invalid state transition (`409 CONFLICT`).
- **What we did instead:**
  We refactored `DatabaseHandoverOperations.completeByDonorOrAdmin` and `reverseByDonorOrAdmin` to lock and fetch the request/listing/handover state by ID first (returning `404` when missing), check ownership explicitly (`!admin && !actorId.equals(donorId)` -> `403 FORBIDDEN`), verify handover/listing states (`409 CONFLICT`), and verify the actor's role in the database via `users.requireRole(actor.id(), actor.role())`.
