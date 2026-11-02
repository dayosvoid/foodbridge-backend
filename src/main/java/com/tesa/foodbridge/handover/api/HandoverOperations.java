package com.tesa.foodbridge.handover.api;

import java.util.UUID;

public interface HandoverOperations {

    void createForApprovedRequest(UUID requestId);

    UUID completeByDonorOrAdmin(
            UUID requestId,
            UUID actorId,
            boolean admin
    );

    UUID reverseByDonorOrAdmin(
            UUID handoverOrRequestId,
            UUID actorId,
            boolean admin
    );
}
