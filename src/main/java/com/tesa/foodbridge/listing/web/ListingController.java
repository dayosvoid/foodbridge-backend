package com.tesa.foodbridge.listing.web;

import com.tesa.foodbridge.cloudinary.CloudinaryService;
import com.tesa.foodbridge.listing.api.CreateListingRequest;
import com.tesa.foodbridge.listing.api.ListingOperations;
import com.tesa.foodbridge.listing.api.ListingResponse;
import com.tesa.foodbridge.shared.Actor;
import com.tesa.foodbridge.shared.ActorFactory;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/listings")
public class ListingController {

    private final ListingOperations listings;
    private final ActorFactory actorFactory;
    private final CloudinaryService cloudinaryService;

    public ListingController(
            ListingOperations listings,
            ActorFactory actorFactory,
            CloudinaryService cloudinaryService) {

        this.listings = listings;
        this.actorFactory = actorFactory;
        this.cloudinaryService = cloudinaryService;
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "basicAuth"
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ListingResponse create(
            @Valid @RequestBody CreateListingRequest request,
            Authentication authentication) {

        Actor actor = actorFactory.from(authentication);

        return toResponse(
                listings.create(
                        actor,
                        new ListingOperations.CreateListing(
                                request.title(),
                                request.description(),
                                request.category(),
                                BigDecimal.valueOf(request.quantity()),
                                request.unit(),
                                request.imageReference(),
                                request.pickupArea(),
                                request.collectionDeadline()
                        )
                )
        );
    }

    @PostMapping(
            value = "/images",
            consumes = "multipart/form-data"
    )
    public Map<String, String> uploadImage(
            @RequestParam("file") MultipartFile file) throws IOException {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Image file cannot be empty");
        }

        String imageUrl = cloudinaryService.uploadImage(file);

        return Map.of("imageUrl", imageUrl);
    }

    @GetMapping
    public List<ListingResponse> browse(
            @RequestParam(required = false)
            String category,

            @RequestParam(required = false)
            String pickupArea,

            @RequestParam(required = false)
            String availability,

            @RequestParam(required = false)
            String keyword,

            @RequestParam(defaultValue = "false")
            boolean mine,

            @RequestParam(defaultValue = "0")
            int offset,

            @RequestParam(defaultValue = "50")
            int limit,

            Authentication authentication) {

        List<ListingOperations.ListingView> result;

        if (mine) {
            result = listings.browseMine(
                    actorFactory.from(authentication),
                    offset,
                    limit
            );
        } else {
            result = listings.browse(
                    offset,
                    limit
            );
        }

        return result.stream()

                .filter(l ->
                        availability == null
                                || l.status()
                                .equalsIgnoreCase(availability)
                )

                .filter(l ->
                        category == null
                                || l.category()
                                .equalsIgnoreCase(category)
                )

                .filter(l ->
                        pickupArea == null
                                || l.pickupArea()
                                .equalsIgnoreCase(pickupArea)
                )

                .filter(l ->
                        keyword == null
                                || (l.title() != null
                                && l.title()
                                .toLowerCase()
                                .contains(keyword.toLowerCase()))

                                || (l.description() != null
                                && l.description()
                                .toLowerCase()
                                .contains(keyword.toLowerCase()))
                )

                .filter(l ->
                        !l.collectionDeadline()
                                .isBefore(Instant.now())
                                || "EXPIRED".equalsIgnoreCase(l.status())
                )

                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{listingId}")
    public ListingResponse get(
            @PathVariable UUID listingId) {

        return toResponse(
                listings.get(listingId)
        );
    }

    private ListingResponse toResponse(
            ListingOperations.ListingView l) {

        return new ListingResponse(
                l.id().toString(),
                l.title(),
                l.description(),
                l.category(),
                l.quantity(),
                l.unit(),
                l.imageUrl(),
                l.pickupArea(),
                l.collectionDeadline(),
                l.status(),
                l.donorId().toString(),
                l.donorName(),
                l.createdAt(),
                l.pendingRequestCount()
        );
    }
}