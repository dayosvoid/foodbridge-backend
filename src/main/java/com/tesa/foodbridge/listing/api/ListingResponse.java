package com.tesa.foodbridge.listing.api;

import java.time.Instant;

public record ListingResponse(
        String id,
        String title,
        String description,
        String category,
        Integer quantity,
        String unit,
        String imageReference,
        String pickupArea,
        Instant collectionDeadline,
        String state,
        String donorId
) {
}