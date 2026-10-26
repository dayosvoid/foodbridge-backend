package com.tesa.foodbridge.listing.web;

import com.tesa.foodbridge.listing.api.CreateListingRequest;
import com.tesa.foodbridge.listing.api.ListingResponse;
import com.tesa.foodbridge.listing.service.ListingApiService;
import com.tesa.foodbridge.reservation.api.FoodRequestResponse;
import com.tesa.foodbridge.reservation.api.ReservationQuery;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/listings")
public class ListingController {

    private final ListingApiService listingApiService;
    private final ReservationQuery reservationQuery;

    public ListingController(
            ListingApiService listingApiService,
            ReservationQuery reservationQuery) {

        this.listingApiService = listingApiService;
        this.reservationQuery = reservationQuery;
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

    @GetMapping("/{listingId}/requests")
    public List<FoodRequestResponse> getRequests(
            @PathVariable String listingId) {

        // Verify that the listing exists
        listingApiService.get(listingId);

        // Return all requests for this listing
        return reservationQuery.findForListing(listingId);
    }

    @GetMapping("/{listingId}")
    public ListingResponse getListing(
            @PathVariable String listingId) {

        return listingApiService.get(listingId);
    }
}