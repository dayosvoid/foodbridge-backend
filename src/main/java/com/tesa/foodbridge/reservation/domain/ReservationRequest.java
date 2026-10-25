package com.tesa.foodbridge.reservation.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name="requests")
public class ReservationRequest {
    @Id private UUID id;
    @Column(name="listing_id", nullable=false) private UUID listingId;
    @Column(name="requester_id", nullable=false) private UUID requesterId;
    @Column(nullable=false, length=20) private String status;
    @Column(name="idempotency_key", length=200) private String idempotencyKey;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="decided_at") private Instant decidedAt;
    protected ReservationRequest() {}
}
