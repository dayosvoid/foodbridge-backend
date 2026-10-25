package com.tesa.foodbridge.activity.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name="activity")
public class Activity {
    @Id private UUID id;
    @Column(name="actor_id",nullable=false) private UUID actorId;
    @Column(name="listing_id",nullable=false) private UUID listingId;
    @Column(nullable=false,length=40) private String type;
    @Column(nullable=false,columnDefinition="text") private String details;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected Activity() {}
}
