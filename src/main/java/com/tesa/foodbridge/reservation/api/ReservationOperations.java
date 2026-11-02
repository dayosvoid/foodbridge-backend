package com.tesa.foodbridge.reservation.api;

import com.tesa.foodbridge.shared.Actor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReservationOperations {

    record RequestView(
            UUID id,
            UUID listingId,
            UUID requesterId,
            String requesterName,
            String status,
            Instant createdAt,
            Instant decidedAt,
            UUID handoverId,
            String handoverStatus
    ) {
        public RequestView(
                UUID id,
                UUID listingId,
                UUID requesterId,
                String requesterName,
                String status,
                Instant createdAt,
                Instant decidedAt) {
            this(id, listingId, requesterId, requesterName, status, createdAt, decidedAt, null, null);
        }
    }

    RequestView request(
            Actor actor,
            UUID listingId,
            String idempotencyKey
    );

    RequestView approve(
            Actor actor,
            UUID requestId
    );

    RequestView decline(
            Actor actor,
            UUID requestId
    );

    RequestView cancel(
            Actor actor,
            UUID requestId
    );

    RequestView get(
            Actor actor,
            UUID requestId
    );

    List<RequestView> forListing(
            Actor actor,
            UUID listingId
    );

    record RequestPage(
            List<RequestView> items,
            long totalCount
    ) {}

    List<RequestView> mine(
            Actor actor
    );

    RequestPage browseAll(
            Actor actor,
            String status,
            int offset,
            int limit
    );
}