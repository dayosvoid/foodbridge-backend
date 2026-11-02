package com.tesa.foodbridge.expiration.api;

import java.util.UUID;

/** Aggregate lifecycle boundary. Caller must authorize before invoking command-time expiration. */
public interface ExpirationOperations {
    boolean expireLocked(UUID listingId);
    void lockAggregate(UUID listingId);
    void expireDue();
}
