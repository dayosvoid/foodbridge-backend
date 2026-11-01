# Listing soft deletion

`DELETE /api/listings/{listingId}` requires authentication and a verified database role.
The owning donor or an admin may delete a listing. A successful call returns 204;
unauthenticated, unauthorized, and missing-listing cases return 401, 403, and 404.
Repeated authorized deletion returns 204 without another audit event. Authorization
is still checked when the listing is already deleted.

Deletion locks the listing and records a `LISTING_ARCHIVED` activity containing
`previous_status` and the complete listing before-image in `details.listing`.
The audit insert and status change to `DELETED` share one transaction. Concurrent
deletions produce exactly one event, and failure of either write rolls back both.
Requests, handovers, and existing activity are preserved.

Public and owner feeds exclude deleted listings before pagination. Anonymous detail
access returns 404 for deleted listings. Historical detail is available to the owning
donor, an admin, or a recipient with a request for the listing; unrelated users receive
403. New requests and approvals reject deleted listings. Existing request history and
authorized cancellation or decline remain available.

## Migration and verification

V5 extends the activity type constraint with `LISTING_ARCHIVED`, preserving all seven
existing types. It does not update domain records or backfill events for previously
deleted listings. V4 continues to define the `DELETED` listing status. V5 has already
been applied to the shared database: preserve the migration's existing content and
use a new migration for any future schema changes.

Run `mvn -B verify` with Java 25 and Docker available. Tests use disposable PostgreSQL
containers and cover the V4-to-V5 upgrade, permitted activity types, authorization,
historical access, pagination, repeated and concurrent deletion, and rollback.

Presentation datasets, maintenance commands, live database reports, approval tokens,
and backup journals are excluded from this implementation commit. Application code
has not been deployed as part of the migration work.
