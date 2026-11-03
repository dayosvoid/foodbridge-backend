package com.tesa.foodbridge.listing.api;

import com.tesa.foodbridge.shared.Actor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ListingOperations {

    record CreateListing(
            String title,
            String description,
            String category,
            BigDecimal quantity,
            String unit,
            String imageUrl,
            String pickupArea,
            Instant collectionDeadline
    ) {}

    record ListingView(
            UUID id,
            UUID donorId,
            String donorName,
            String title,
            String description,
            String category,
            BigDecimal quantity,
            String unit,
            String imageUrl,
            String pickupArea,
            Instant collectionDeadline,
            String status,
            Instant createdAt,
            Instant updatedAt,
            int pendingRequestCount,
            Instant expiredAt,
            String expirationReason
    ) {
        public ListingView(UUID id, UUID donorId, String donorName, String title, String description,
                String category, BigDecimal quantity, String unit, String imageUrl, String pickupArea,
                Instant collectionDeadline, String status, Instant createdAt, Instant updatedAt, int pendingRequestCount) {
            this(id,donorId,donorName,title,description,category,quantity,unit,imageUrl,pickupArea,
                    collectionDeadline,status,createdAt,updatedAt,pendingRequestCount,null,null);
        }
    }

    record ListingFilter(
            String category,
            String pickupArea,
            String availability,
            String keyword
    ) {
        public static final ListingFilter NONE =
                new ListingFilter(null, null, null, null);
    }

    record ListingPage(
            List<ListingView> items,
            long totalCount
    ) {}

    ListingView create(Actor actor, CreateListing command);

    ListingView update(Actor actor, UUID id, CreateListing command);

    ListingView get(UUID id);

    /** Idempotent soft deletion; preserves requests, handovers and history. */
    void delete(Actor actor, UUID id);

    /** Public detail access excludes deleted rows; internal get preserves history workflows. */
    ListingView getPublic(UUID id);

    /** Deleted history is available only to admin, owner, or a recipient who requested it. */
    ListingView getHistorical(Actor actor, UUID id);

    List<ListingView> browse(int offset, int limit);

    ListingPage browse(ListingFilter filter, int offset, int limit);

    ListingPage browseAdmin(Actor actor, ListingFilter filter, int offset, int limit);

    List<ListingView> browseMine(
            Actor actor,
            int offset,
            int limit
    );

    ListingPage browseMine(
            Actor actor,
            ListingFilter filter,
            int offset,
            int limit
    );

    ListingView lockForRequest(UUID id);

    ListingView lockForApproval(UUID id, Actor actor);

    void markReserved(UUID id);
}
