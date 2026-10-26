package com.tesa.foodbridge.listing.api;

import com.tesa.foodbridge.shared.Actor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ListingOperations {
    record CreateListing(String title, String description, String category, BigDecimal quantity,
                         String unit, String imageUrl, String pickupArea, Instant collectionDeadline) {}
    record ListingView(UUID id, UUID donorId, String title, String description, String category,
                       BigDecimal quantity, String unit, String imageUrl, String pickupArea,
                       Instant collectionDeadline, String status, Instant createdAt, Instant updatedAt) {}
    ListingView create(Actor actor, CreateListing command);
    ListingView get(UUID id);
    List<ListingView> browse(int offset, int limit);
    /** Must be called inside a transaction; locks the listing against deadline/status edits. */
    ListingView lockForRequest(UUID id);
    ListingView lockForApproval(UUID id, Actor actor);
    void markReserved(UUID id);
}
