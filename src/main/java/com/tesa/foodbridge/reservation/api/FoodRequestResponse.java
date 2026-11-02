package com.tesa.foodbridge.reservation.api;

import java.time.Instant;

public record FoodRequestResponse(
        String id,
        String listingId,
        String recipientId,
        String recipientName,
        String status,
        Instant createdAt,
        String handoverId,
        String handoverStatus
) {
    public FoodRequestResponse(
            String id,
            String listingId,
            String recipientId,
            String recipientName,
            String status,
            Instant createdAt) {
        this(id, listingId, recipientId, recipientName, status, createdAt, null, null);
    }
}
