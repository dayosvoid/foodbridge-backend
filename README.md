# FoodBridge Backend

Team 1 TeSA Cohort 6 capstone — **independent Git repository**.

FoodBridge is a food-sharing application that connects donors with recipients to help coordinate surplus food listings and food requests.

## Technology Stack

- Java 25
- Spring Boot 3.5.7
- Maven
- PostgreSQL
- Flyway database migrations
- Spring Security
- OpenAPI for API contracts and documentation
- Cloudinary for listing image uploads

The backend follows a **modular monolith** architecture and maintains its own PostgreSQL database.

## Run Locally

1. Install JDK 25 and Maven.
2. Confirm your installations:

   ```bash
   java -version
   mvn -version
   ```

3. Start a separate PostgreSQL database for this application.
4. Configure the environment variables listed in `.env.example`. Do not commit passwords, API keys, or other secrets.
5. Run the application:

   ```bash
   mvn spring-boot:run
   ```

6. Run the tests:

   ```bash
   mvn test
   ```

7. Run full Maven verification:

   ```bash
   mvn -B verify
   ```

Full verification requires Docker because the integration tests use disposable PostgreSQL containers. The tests should not require production database credentials.

## Module Rules

Each business module follows the package structure:

- `api` — contracts exposed to other modules.
- `domain` — business entities and rules.
- `repository` — data access.
- `service` — application and business operations.
- `web` — HTTP controllers and endpoint handling.

Other modules should use only a module's `api` package rather than accessing its internal implementation directly.

Lombok is not used.

## API and Access

The backend API includes work for the following areas:

- **Authentication:** Login (`POST /api/auth/login`) and current-user profile (`GET /api/auth/me`) endpoints using opaque Bearer tokens.
- **Listings:** Create, browse, filter (`category`, `pickupArea`, `availability`, `keyword`, `mine`), paginate (`offset`, `limit`, `X-Total-Count` response header), edit, and soft-delete (`DELETE /api/listings/{listingId}`) food listings.
  - Default `limit` is `50` (minimum `1`, maximum `100`), supporting both recipient browsing (`limit=12`) and admin table views (`limit=50`).
  - Supported `availability` filter values: `AVAILABLE` (excludes overdue listings), `RESERVED`, `COLLECTED`, `REVERSED`, and `EXPIRED` (includes overdue `AVAILABLE` listings; for `ADMIN` callers, also includes soft-deleted listings with `deleted=true` and `deletedAt`).
- **Requests & Repeat-Submission Safety (Idempotency):**
  - `POST /api/listings/{listingId}/requests` requires an `Idempotency-Key` header (1–200 characters). Submitting the same request twice with the same `Idempotency-Key` for the same recipient and listing safely replays the original `FoodRequest` without creating a duplicate row. Reusing a key across a different listing or recipient returns `409 Conflict` (`IDEMPOTENCY_KEY_REUSED`).
  - `GET /api/requests?mine=true` returns only the authenticated recipient's own requests.
  - `GET /api/admin/requests` (`ADMIN` only) returns all platform requests with optional `status` filter (`PENDING`, `APPROVED`, `DECLINED`, `CANCELLED`, `EXPIRED`), `offset`, `limit`, and `X-Total-Count` header.
- **Handovers:**
  - `PATCH /api/requests/{requestId}/collected` and `PATCH /api/handovers/{handoverId}/reverse` allow the listing-owning `DONOR` or an `ADMIN` (verified against the database `users` table) to mark an approved request collected or reverse a completed handover.
- **Admin Dashboard:**
  - `GET /api/admin/dashboard` (`ADMIN` only) returns live counts including `expiredListings` and unit-safe `estimatedPortionsSaved` (summing `portions_estimate` or `PORTIONS`-unit quantities from `COLLECTED` handovers, automatically excluding `REVERSED` handovers and incompatible units like `KG`, `LITRES`, `ITEMS`, and `BOXES`).
- **CORS:**
  - Configured for `https://food-bridge-ruby.vercel.app`, `https://frontend-six-zeta-39.vercel.app`, and `http://localhost:5173`, allowing `GET, POST, PUT, PATCH, DELETE, OPTIONS`, headers `Authorization, Content-Type, X-Request-Id, Idempotency-Key`, and exposing `X-Request-Id` and `X-Total-Count`.
- **Structured Logging & Correlation:**
  - `RequestIdFilter` accepts a valid incoming `X-Request-Id` header (or generates a UUID), echoes `X-Request-Id` on every response, and binds `requestId` in SLF4J MDC for structured ECS JSON logging.

## Demonstration Accounts (Fictional Only)

All seeded users and organisations are **fictional demonstration accounts** (`@demo.foodbridge.invalid`) active only under `SPRING_PROFILES_ACTIVE=demo` (and disabled if `prod` or `production` is active):

| Role | Email | Password | Deterministic UUID |
| :--- | :--- | :--- | :--- |
| Donor 1 | `donor1@demo.foodbridge.invalid` | `donor123` | `487c47b3-65e8-3ca1-bccb-7ee2a44172d6` |
| Donor 2 | `donor2@demo.foodbridge.invalid` | `donor223` / `donor123` (`donor123` in demo auth) | `0b512802-54ad-333b-9757-977db2c5deff` |
| Recipient 1 | `recipient1@demo.foodbridge.invalid` | `recipient123` | `c8840d79-f188-3367-a470-d3dc9609a640` |
| Recipient 2 | `recipient2@demo.foodbridge.invalid` | `recipient123` | `668cefe8-0c95-34f2-9823-92ac376b8cf8` |
| Recipient 3 | `recipient3@demo.foodbridge.invalid` | `recipient123` | `034f933b-74f1-3e77-b2ac-a069fe91c637` |
| Administrator | `admin@demo.foodbridge.invalid` | `admin123` | `839b18f3-86b7-395d-9311-579e435f1435` |

## Three End-to-End Clean-Start Demonstration Journeys

1. **Journey 1 — Donor Publishes Surplus Food, Recipient Requests with Idempotent Retry, Owning Donor Approves (Concurrency-Protected):**
   - Log in as `donor1@demo.foodbridge.invalid` (`POST /api/auth/login`) and create a listing (`POST /api/listings` with `unit: "PORTIONS"` and `quantity: 20`).
   - Log in as `recipient1@demo.foodbridge.invalid` and submit `POST /api/listings/{listingId}/requests` with `Idempotency-Key: journey1-req-1`. Repeat the exact POST to verify safe idempotent replay.
   - Log in as `donor1@demo.foodbridge.invalid` and approve the request (`PATCH /api/requests/{requestId}/approve`); listing transitions atomically to `RESERVED` and a handover is created in `AWAITING_COLLECTION`.
2. **Journey 2 — Collection Confirmation, Dashboard Portions Estimate, and Handover Reversal:**
   - As `donor1@demo.foodbridge.invalid` (or `admin@demo.foodbridge.invalid`), call `PATCH /api/requests/{requestId}/collected`.
   - As `admin@demo.foodbridge.invalid`, call `GET /api/admin/dashboard` and verify `collectedListings`, `completedHandovers`, and `estimatedPortionsSaved` increased by 20.
   - Call `PATCH /api/handovers/{handoverId}/reverse` and re-check `GET /api/admin/dashboard` to verify `estimatedPortionsSaved` moved back by 20.
3. **Journey 3 — Admin Monitoring (`GET /api/admin/requests`) and Expired Listing Soft-Deletion with Audit Retention:**
   - As `admin@demo.foodbridge.invalid`, call `GET /api/admin/requests?status=PENDING&offset=0&limit=50` and inspect `X-Total-Count` and pending request records.
   - As `donor1@demo.foodbridge.invalid`, soft-delete an `AVAILABLE` or `EXPIRED` listing via `DELETE /api/listings/{listingId}` (`204 No Content`).
   - Verify the deleted listing no longer appears in public `GET /api/listings`, while `GET /api/listings?availability=EXPIRED&limit=50` authenticated as `admin@demo.foodbridge.invalid` still displays the record with `status: "DELETED"`, `deleted: true`, and `deletedAt` populated, and `GET /api/activity` records the `LISTING_ARCHIVED` JSONB snapshot.

## Five Most Important Database Queries & Index Analysis

1. **Filtered & Paginated Listing Browse (`ListingRepository.queryFiltered`)**
   - *Query:* `SELECT l.*, u.display_name, (SELECT COUNT(*) FROM requests r WHERE r.listing_id = l.id AND r.status = 'PENDING') FROM listings l JOIN users u ON u.id = l.donor_id WHERE l.status <> 'DELETED' [AND l.status = 'AVAILABLE' AND l.collection_deadline > ?] ORDER BY l.created_at DESC, l.id LIMIT ? OFFSET ?`
   - *Index Serving It:* `listings_browse_idx ON listings(status, collection_deadline)` and PK `users(id)`.
   - *Rows Examined vs. Returned:* Examines matching active status/deadline index entries (e.g., ~15–40 rows in demo dataset) and returns at most `limit` rows (12 or 50).
   - *Growth Behaviour:* Filtering by `status` and `collection_deadline` uses `listings_browse_idx`; sorting by `created_at DESC` over the filtered subset scales well when active listings remain bounded relative to historical rows.
2. **Donor's Own Listings (`ListingRepository.browseMine`)**
   - *Query:* `SELECT ... FROM listings l JOIN users u ON u.id = l.donor_id WHERE l.donor_id = ? AND l.status <> 'DELETED' ORDER BY l.created_at DESC, l.id LIMIT ? OFFSET ?`
   - *Index Serving It:* `listings_donor_idx ON listings(donor_id)`.
   - *Rows Examined vs. Returned:* Examines only rows owned by `donor_id` (~20 rows per donor) and returns up to `limit` rows.
   - *Growth Behaviour:* Scales linearly with a single donor's listing count rather than total platform listings.
3. **Single Approved Reservation Enforcement & Listing Requests (`RequestRepository.approve` / `findForListing`)**
   - *Query:* `UPDATE requests SET status = 'APPROVED', decided_at = clock_timestamp() WHERE id = ? AND status = 'PENDING'` protected by `one_approved_request_per_listing`.
   - *Index Serving It:* Primary key `requests(id)`, `requests_listing_idx ON requests(listing_id)`, and partial unique index `one_approved_request_per_listing ON requests(listing_id) WHERE status = 'APPROVED'`.
   - *Rows Examined vs. Returned:* 1 row examined via PK + 1 index entry checked in the partial unique index; 1 row updated.
   - *Growth Behaviour:* $O(\log N)$ index lookup; does not degrade as historical declined/cancelled/expired requests accumulate because the partial unique index only indexes rows where `status = 'APPROVED'`.
4. **Recipient's Own Requests & Idempotent Replay (`RequestRepository.findMine` / `byKey`)**
   - *Query:* `SELECT r.*, u.display_name FROM requests r JOIN users u ON u.id = r.requester_id WHERE r.requester_id = ? ORDER BY r.created_at DESC, r.id` and `WHERE r.idempotency_key = ?`.
   - *Index Serving It:* `requests_requester_idx ON requests(requester_id)` and unique constraint index `requests_idempotency_key_key ON requests(idempotency_key)`.
   - *Rows Examined vs. Returned:* `byKey` examines at most 1 row and returns 0 or 1; `findMine` examines only that recipient's requests.
   - *Growth Behaviour:* Constant/logarithmic lookup via B-tree index; does not scan unrelated requests.
5. **Listing Activity Audit History (`RecentActivityController.getRecentActivity`)**
   - *Query:* `SELECT a.*, u.display_name, l.title FROM activity a LEFT JOIN users u ON u.id = a.actor_id LEFT JOIN listings l ON l.id = a.listing_id ORDER BY a.created_at DESC LIMIT ?`
   - *Index Serving It:* `activity_listing_created_idx ON activity(listing_id, created_at)` (for listing-scoped audit lookups) and `activity_actor_idx ON activity(actor_id)`.
   - *Rows Examined vs. Returned:* Examines `activity` rows for top-$N$ sort and joins `users` and `listings` by primary key, returning up to `limit` (default 20, max 100) rows.
   - *Growth Behaviour:* Global `ORDER BY created_at DESC` performs a top-$N$ heap sort until a standalone `(created_at DESC)` index is needed at high audit volumes.
- **One Index Deliberately Not Created:** We deliberately did **not** create a full-text GIN index (`to_tsvector`) on `listings(title, description)`. At FoodBridge's current active-listing cardinality (tens to hundreds of open surplus offers in a city), the B-tree filter on `(status, collection_deadline)` narrows candidates first, and a GIN index would add write amplification and storage overhead on every listing creation and edit without measurable read latency benefit.

## Cross-Tier Collaboration Note (Engineer B: Esther Ebagua)

- **One thing the interface tier did that saved time:** Supplying a structured, endpoint-by-endpoint integration feedback document (`ESTHER adjustment.docx`) with exact query strings (`GET /api/listings?offset=0&limit=12&availability=AVAILABLE`), expected status enum values, and exact Vercel origin (`https://food-bridge-ruby.vercel.app`) eliminated guesswork when aligning pagination headers (`X-Total-Count`) and CORS preflight rules.
- **One thing the interface tier did that cost time:** Initially calling `GET /api/listings` expecting the `50`-item default page to be filtered in-memory without checking how `offset`/`limit` interacted with `availability` and without a dedicated admin requests endpoint (`GET /api/admin/requests`), which required a coordinated second pass on pagination headers and admin request filtering.

## API Documentation

The OpenAPI contract is located at:

`contracts/openapi.yaml`

To validate it with Redocly CLI:

```bash
npx --yes @redocly/cli lint contracts/openapi.yaml
```

The production backend URL is:

`https://foodbridge-backend-rg9f.onrender.com`

The local development server uses port `8080`, unless overridden by configuration.

## Database and Migrations

PostgreSQL is the backend's database, and Flyway manages database schema migrations.

Migration files are maintained under:

`src/main/resources/db/migration`

Database schema changes and business rules must remain consistent with the team's agreed module ownership and integration contracts.

## Team Branches and Responsibilities

- **Adedayo — `dayo/data-rules`:** Schema, Flyway migrations, database rules, seed data, and database/concurrency tests.
- **Esther — API, access, and deployment work:** OpenAPI, controllers, authentication, role checks, CI, and deployment.

Confirm the active Git branch before making commits or opening pull requests.

## Continuous Integration

The GitHub Actions CI workflow runs Maven verification. It checks the build and configured tests; it does not itself deploy the application.

The CI database configuration should use a temporary test database rather than production credentials.

## Verification Status

The following verification has been reported during development:

- The OpenAPI contract passed Redocly validation, with warnings.
- `mvn -B verify` completed successfully after the listing naming-convention changes.
- 20 tests passed, 0 failures, 0 errors, 0 skipped; Maven verification successful on 29 October 2026.
