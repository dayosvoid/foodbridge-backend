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
            int pendingRequestCount
    ) {}

    ListingView create(Actor actor, CreateListing command);

    ListingView update(Actor actor, UUID id, CreateListing command);

    void delete(Actor actor, UUID id);

    ListingView get(UUID id);

    List<ListingView> browse(int offset, int limit);

    List<ListingView> browseMine(
            Actor actor,
            int offset,
            int limit
    );

    ListingView lockForRequest(UUID id);

    ListingView lockForApproval(UUID id, Actor actor);

    void markReserved(UUID id);
}