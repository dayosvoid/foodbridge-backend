package com.tesa.foodbridge.distribution.api;

/**
 * Daily first-round distribution snapshot,
 * using Africa/Lagos calendar days.
 */
public record DailyDistributionSummary(
        long totalRecipients,
        long availableListings,
        long approvedListingsToday,
        long recipientsServedToday,
        long recipientsWaitingToday,
        boolean firstRoundInProgress
) {}