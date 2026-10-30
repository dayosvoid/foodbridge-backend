package com.tesa.foodbridge.reservation.api;

import java.time.Instant;

public record FoodRequestResponse(
        String id,
        String listingId,
        String recipientId,
        String recipientName,
        String status,
        Instant createdAt
) {}