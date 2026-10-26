package com.tesa.foodbridge.listing.web;

import com.tesa.foodbridge.listing.api.CreateListingRequest;
import com.tesa.foodbridge.listing.api.ListingResponse;
import com.tesa.foodbridge.listing.service.ListingApiService;
import com.tesa.foodbridge.reservation.api.FoodRequestResponse;
import com.tesa.foodbridge.reservation.service.ReservationApiService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/listings")
public class ListingController {

    private final ListingApiService listingApiService;
    private final ReservationApiService reservationApiService;

    public ListingController(
            ListingApiService listingApiService,
            ReservationApiService reservationApiService) {

        this.listingApiService = listingApiService;
        this.reservationApiService = reservationApiService;
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

    /**
     * Returns all requests made against a specific listing.
     *
     * Used by the donor so they can see who requested
     * their food listing and decide whether to approve.
     */
    @GetMapping("/{listingId}/requests")
    public List<FoodRequestResponse> getRequests(
            @PathVariable String listingId) {

        // Make sure the listing exists
        listingApiService.get(listingId);

        return reservationApiService.findForListing(listingId);
    }

    @GetMapping("/{listingId}")
    public ListingResponse getListing(
            @PathVariable String listingId) {

        return listingApiService.get(listingId);
    }
}