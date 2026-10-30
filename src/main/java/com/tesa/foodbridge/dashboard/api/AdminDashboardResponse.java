package com.tesa.foodbridge.dashboard.api;

public record AdminDashboardResponse(
        long totalUsers,
        long totalDonors,
        long totalRecipients,
        long totalListings,
        long availableListings,
        long reservedListings,
        long collectedListings,
        long totalRequests,
        long pendingRequests,
        long completedHandovers
) {}