# Architecture and API decisions

Record date, decision, alternatives, tradeoff and approval from both engineers.

---

## 1. Contract Change Log (`contracts/openapi.yaml`)

- **2026-10-22** — Initial OpenAPI 3.0.3 contract (`contracts/openapi.yaml`) defining authentication, listings, requests, handovers, dashboard, and activity endpoints. Agreed by **Adedayo Aboderin** and **Esther Ebagua** (with **Muizz Jamiu**).
- **2026-10-29** — Aligned listing DTO field names (`foodName`, `imageReference`, `recipientId`, `recipientName`) and bearer token authentication across `contracts/openapi.yaml` and web controllers. Agreed by **Esther Ebagua** and **Muizz Jamiu**.
- **2026-10-31** — Added atomic listing soft-deletion (`DELETE /api/listings/{listingId}`), `DELETED` listing status, and `LISTING_ARCHIVED` audit activity. Agreed by **Adedayo Aboderin** and **Esther Ebagua**.
- **2026-11-01** — Added `X-Total-Count` response header to `GET /api/listings`, added `GET /api/admin/requests` (with `status`, `offset`, `limit`, and `X-Total-Count`), added `expiredListings` and `estimatedPortionsSaved` to `AdminDashboardResponse`, added `deleted` and `deletedAt` to `ListingResponse`, and permitted owning donor/admin soft-deletion of `EXPIRED` listings with admin historical visibility. Agreed by **Esther Ebagua** and **Muizz Jamiu** (coordinated with **Adedayo Aboderin**).

---

## 2. Five Engineering Decision Records

### Decision Record 1 — Database-Protected At-Most-One Approved Reservation Rule

- **Context:** FoodBridge requires that a food listing holds at most one approved reservation at a time, even when three recipients submit or have requests approved simultaneously. An application-only read-then-write check can be bypassed under concurrent requests at `READ COMMITTED` isolation.
- **Decision:** Enforce the invariant in PostgreSQL using both a pessimistic row lock (`SELECT ... FOR UPDATE` on the `listings` row inside a `REQUIRES_NEW` `READ_COMMITTED` transaction) and a partial unique index `CREATE UNIQUE INDEX one_approved_request_per_listing ON requests (listing_id) WHERE status = 'APPROVED'` (`V2__one_approved_request_per_listing.sql`).
- **Alternatives Rejected:**
  1. *Application-level `synchronized` block or read-before-write query:* Rejected because it fails across multiple service instances, can be bypassed by direct SQL/JDBC access, and violates the service-tier requirement that database rules be protected by database constraints and transactions.
  2. *`SERIALIZABLE` isolation on all transactions:* Rejected because it causes high retry overhead across unrelated reads and still benefits from explicit unique index enforcement for clear constraint diagnostics (`SQLSTATE 23505`).
- **Named Cost Accepted:** Pessimistic locking serializes concurrent approval attempts on the same listing row, adding lock-wait latency under contention and requiring explicit translation of `SQLSTATE 23505` into HTTP `409 Conflict` (`LISTING_ALREADY_RESERVED`).
- **Observation That Would Reverse the Decision:** If FoodBridge evolves to allow multi-recipient split allocations (e.g., partial portion claims against a single bulk listing), we would replace the single-approved-request partial unique index with a transactional ledger table and a database `CHECK (remaining_quantity >= 0)` constraint on the listing.

---

### Decision Record 2 — Explicit Admin Requests Endpoint (`GET /api/admin/requests`) vs. Overloading `GET /api/requests?mine=false`

- **Context:** Administrators need to inspect the actual pending and historical request records behind the dashboard counts, whereas recipients use `GET /api/requests?mine=true` to view only their own requests.
- **Decision:** Keep `GET /api/requests?mine=true` strictly for authenticated recipients' own requests (rejecting `mine=false` with `400 MINE_REQUIRED`) and expose a dedicated administrator-only endpoint `GET /api/admin/requests` supporting `status`, `offset`, `limit`, and `X-Total-Count`.
- **Alternatives Rejected:**
  1. *Allowing `GET /api/requests?mine=false` to return all platform requests when the caller is an admin:* Rejected because a boolean flag changing an endpoint from user-scoped to platform-wide increases the risk of accidental privilege leakage if a role check is ever misconfigured.
  2. *Fetching requests per listing in a loop (`GET /api/listings/{id}/requests`) on the frontend:* Rejected because it causes an $N+1$ HTTP request waterfall across pages of listings.
- **Named Cost Accepted:** Maintaining a second request-listing endpoint in `ReservationController` and `contracts/openapi.yaml`.
- **Observation That Would Reverse the Decision:** If the platform introduces a unified cursor-paginated search/audit API across all resources with mandatory scope filters, we would consolidate admin and owner request queries under that unified contract.

---

### Decision Record 3 — Atomic Soft-Deletion with JSONB Snapshot Auditing (`DELETED` Status + `LISTING_ARCHIVED`)

- **Context:** Donors need to remove listings they created (when `AVAILABLE` or `EXPIRED`), while administrators must retain full audit visibility of expired and deleted listings and their associated request/handover history.
- **Decision:** Implement deletion as an atomic soft-delete transaction that locks the listing row, records a `LISTING_ARCHIVED` activity containing `previous_status` and the full JSONB before-image (`to_jsonb(l)`), transitions `listings.status` to `DELETED`, and exposes `deleted` (`boolean`) and `deletedAt` (`Instant`) on `ListingResponse`. Public and donor feeds exclude `DELETED` rows before pagination, while admin listing queries include them alongside expired records.
- **Alternatives Rejected:**
  1. *Physical `DELETE FROM listings` with `ON DELETE CASCADE`:* Rejected because it destroys historical requests, handovers, and audit trails required by administrators and dashboard metrics.
  2. *Moving deleted listings to a separate archive table:* Rejected because foreign keys from `requests`, `handovers`, and `activity` reference `listings(id)`, and splitting tables would break historical joins.
- **Named Cost Accepted:** Every public listing and dashboard count query must explicitly exclude `status <> 'DELETED'` so soft-deleted rows do not inflate active or public counts.
- **Observation That Would Reverse the Decision:** If `DELETED` rows grow to dominate table storage and slow down active browse queries despite partial indexing, we would partition `listings` by status or archive rows older than a retention window into cold storage.

---

### Decision Record 4 — Read-Time Plus Scheduled Expiry Evaluation (`collection_deadline` vs. `EXPIRED` Status)

- **Context:** A listing's `collection_deadline` can pass between scheduled background runs (or if the background scheduler is delayed for an hour). The brief requires that an overdue listing is never shown as available or accepted for new requests/approvals even before a background job runs.
- **Decision:** Enforce deadline eligibility at read and write time directly in SQL (`l.status = 'AVAILABLE' AND l.collection_deadline > clock_timestamp()` for `AVAILABLE` filter/dashboard counts, and `l.status = 'EXPIRED' OR (l.status = 'AVAILABLE' AND l.collection_deadline <= clock_timestamp())` for `EXPIRED` filter/dashboard counts), backed by `DefaultListingOperations.expireListings()` (`@Scheduled(fixedRate = 60000)`) to persist the `EXPIRED` state.
- **Alternatives Rejected:**
  1. *Relying solely on the 60-second `@Scheduled` job to update `status = 'EXPIRED'`:* Rejected because up to 60 seconds (or longer during a scheduler stall) of stale `AVAILABLE` rows would appear in recipient browse feeds and skew `X-Total-Count`.
  2. *Mutating overdue rows to `EXPIRED` inside `GET /api/listings`:* Rejected because HTTP `GET` must remain safe and side-effect-free (a read must never mutate database state).
- **Named Cost Accepted:** Read queries for `AVAILABLE` and `EXPIRED` evaluate both `status` and `collection_deadline` against a single query timestamp, supported by the composite index `listings_browse_idx (status, collection_deadline)`.
- **Observation That Would Reverse the Decision:** If database read replicas or event-driven expiration triggers are introduced with sub-second guarantees, we would evaluate whether status-only predicates suffice.

---

### Decision Record 5 — Unit-Safe Portions Saved Estimation on Admin Dashboard

- **Context:** The admin dashboard displays `estimatedPortionsSaved` derived from completed (`COLLECTED`) handovers, and must decrease when a handover is reversed. However, listings support multiple units (`PORTIONS`, `KG`, `LITRES`, `ITEMS`, `BOXES`).
- **Decision:** Compute `estimatedPortionsSaved` dynamically in SQL over `handovers WHERE status = 'COLLECTED'` by summing `handovers.portions_estimate` when explicitly recorded, or `ROUND(listings.quantity)` only when `listings.unit = 'PORTIONS'`, contributing `0` for incompatible units (`KG`, `LITRES`, `ITEMS`, `BOXES`) unless a portions estimate is recorded. When a handover transitions to `REVERSED`, its status is no longer `COLLECTED`, so the sum automatically moves back.
- **Alternatives Rejected:**
  1. *Summing raw `listings.quantity` across all units (`KG + LITRES + BOXES + PORTIONS`):* Rejected because adding incompatible physical units produces a meaningless number and violates the specification.
  2. *Storing a running counter in a dashboard summary table:* Rejected because stored counters can drift from actual handover records and the specification requires dashboard figures to be computed from recorded activity.
- **Named Cost Accepted:** Listings measured in `KG`, `LITRES`, `ITEMS`, or `BOXES` without an explicit `portions_estimate` do not increment `estimatedPortionsSaved` (though they still increment `completedHandovers` and `collectedListings`).
- **Observation That Would Reverse the Decision:** If the product introduces a mandatory donor-supplied `estimatedPortions` field on every listing creation form regardless of unit, we would populate `handovers.portions_estimate` from that field on handover creation.

