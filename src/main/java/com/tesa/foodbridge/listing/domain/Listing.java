package com.tesa.foodbridge.listing.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Persistence mapping validated by Hibernate; writes are explicit SQL in the owning repository. */
@Entity
@Table(name = "listings")
public class Listing {
    @Id private UUID id;
    @Column(name="donor_id", nullable=false) private UUID donorId;
    @Column(nullable=false, length=300) private String title;
    @Column(nullable=false, columnDefinition="text") private String description;
    @Column(nullable=false, length=30) private String category;
    @Column(nullable=false, precision=12, scale=2) private BigDecimal quantity;
    @Column(nullable=false, length=30) private String unit;
    @Column(name="image_url", columnDefinition="text") private String imageUrl;
    @Column(name="pickup_area", nullable=false, length=200) private String pickupArea;
    @Column(name="collection_deadline", nullable=false) private Instant collectionDeadline;
    @Column(nullable=false, length=30) private String status;
    @Column(name="created_at", nullable=false) private Instant createdAt;
    @Column(name="updated_at", nullable=false) private Instant updatedAt;
    protected Listing() {}
}
