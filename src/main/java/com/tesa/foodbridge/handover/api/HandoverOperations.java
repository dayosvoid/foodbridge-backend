package com.tesa.foodbridge.handover.api;

import java.util.UUID;

public interface HandoverOperations {

    void createForApprovedRequest(UUID requestId);

    void completeByDonorOrAdmin(
            UUID requestId,
            UUID actorId,
            boolean admin
    );

    void reverseByDonorOrAdmin(
            UUID handoverId,
            UUID actorId,
            boolean admin
    );
}