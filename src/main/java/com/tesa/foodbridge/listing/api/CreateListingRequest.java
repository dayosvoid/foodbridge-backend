package com.tesa.foodbridge.listing.api;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
public record CreateListingRequest(
        @NotBlank String title,
        String description,
        @NotBlank String category,
        @Min(1) int quantity,
        String unit,
        String imageReference,
        @NotBlank String pickupArea,
        @NotNull Instant collectionDeadline
) {}
