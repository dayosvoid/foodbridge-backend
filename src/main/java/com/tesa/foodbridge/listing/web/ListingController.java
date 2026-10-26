package com.tesa.foodbridge.listing.web;

import com.tesa.foodbridge.listing.api.CreateListingRequest;
import com.tesa.foodbridge.listing.api.ListingResponse;
import com.tesa.foodbridge.listing.service.ListingApiService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/listings")
public class ListingController {

    private final ListingApiService listingApiService;

    public ListingController(ListingApiService listingApiService) {
        this.listingApiService = listingApiService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ListingResponse createListing(
            @Valid @RequestBody CreateListingRequest request) {

        return listingApiService.create(
                request,
                "current-user"
        );
    }

    @GetMapping
    public List<ListingResponse> getListings(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String pickupArea,
            @RequestParam(required = false) String availability,
            @RequestParam(required = false) String keyword) {

        return listingApiService.browse(
                category,
                pickupArea,
                availability,
                keyword
        );
    }

    @GetMapping("/{listingId}")
    public ListingResponse getListing(
            @PathVariable String listingId) {

        return listingApiService.get(listingId);
    }
}