
package com.tesa.foodbridge.listing.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateListingRequest(

        @NotBlank
        String foodName,

        @NotBlank
        String description,

        @NotBlank
        @Pattern(
                regexp = "PREPARED_MEALS|PRODUCE|BAKERY|DAIRY|PANTRY|OTHER"
        )
        String category,

        @NotNull
        @DecimalMin("0.01")
        @Digits(integer = 10, fraction = 2)
        BigDecimal quantity,

        @NotBlank
        @Pattern(
                regexp = "PORTIONS|KG|LITRES|ITEMS|BOXES"
        )
        String unit,

        String imageReference,

        @NotBlank
        String pickupArea,

        @NotNull
        Instant collectionDeadline

) {}
