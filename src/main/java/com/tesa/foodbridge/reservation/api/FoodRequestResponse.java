package com.tesa.foodbridge.reservation.api;

import java.time.Instant;

public record FoodRequestResponse(
        String id,
        String listingId,
        String requesterId,
        String requesterName,
        String state,
        Instant createdAt
) {}