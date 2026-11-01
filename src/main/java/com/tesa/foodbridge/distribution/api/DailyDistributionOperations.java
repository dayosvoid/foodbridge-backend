package com.tesa.foodbridge.distribution.api;

import java.util.UUID;

/**
 * Public contract for daily recipient allocation rules and monitoring.
 */
public interface DailyDistributionOperations {

    void assertRecipientEligibleForApproval(UUID recipientId);

    DailyDistributionSummary getTodaySummary();
}