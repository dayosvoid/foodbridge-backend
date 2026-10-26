package com.tesa.foodbridge.reservation.api;

import com.tesa.foodbridge.shared.Actor;
import java.time.Instant;
import java.util.UUID;

public interface ReservationOperations {
    record RequestView(UUID id, UUID listingId, UUID requesterId, String status, Instant createdAt, Instant decidedAt) {}
    RequestView request(Actor actor, UUID listingId, String idempotencyKey);
    RequestView approve(Actor actor, UUID requestId);
    RequestView get(Actor actor, UUID requestId);
}
