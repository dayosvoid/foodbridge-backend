package com.tesa.foodbridge.listing.api;

import java.math.BigDecimal;
import java.time.Instant;

public record ListingResponse(
        String id,
        String title,
        String description,
        String category,
        BigDecimal quantity,
        String unit,
        String imageReference,
        String pickupArea,
        Instant collectionDeadline,
        String state,
        String donorId,
        String donorName,
        Instant createdAt,
        int pendingRequestCount
) {}