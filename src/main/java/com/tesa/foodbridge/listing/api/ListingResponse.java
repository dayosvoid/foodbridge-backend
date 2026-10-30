package com.tesa.foodbridge.listing.api;

import java.math.BigDecimal;
import java.time.Instant;

public record ListingResponse(
        String id,
        String foodName,
        String description,
        String category,
        BigDecimal quantity,
        String unit,
        String imageReference,
        String pickupArea,
        Instant collectionDeadline,
        String status,
        String donorId,
        String donorName,
        Instant createdAt,
        int pendingRequestCount
) {}