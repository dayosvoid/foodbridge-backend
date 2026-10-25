package com.tesa.foodbridge.handover.domain;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
@Entity
@Table(name="handovers")
public class Handover {
    @Id private UUID id;
    @Column(name="request_id",nullable=false) private UUID requestId;
    @Column(nullable=false,length=30) private String status;
    @Column(name="portions_estimate") private Integer portionsEstimate;
    @Column(name="completed_at") private Instant completedAt;
    @Column(name="reversed_at") private Instant reversedAt;
    protected Handover() {}
}
