package com.tesa.foodbridge.listing.service;

import com.tesa.foodbridge.listing.api.CreateListingRequest;
import com.tesa.foodbridge.listing.api.ListingResponse;
import com.tesa.foodbridge.shared.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ListingApiService {
    private final ConcurrentHashMap<String, ListingResponse> listings = new ConcurrentHashMap<>();

    public ListingApiService() {
        var seed = new ListingResponse("listing-001", "Fresh Bread", "Surplus bread available for collection.", "Bakery", 50, "portions", null, "Lekki", Instant.now().plusSeconds(86400), "AVAILABLE", "donor1");
        listings.put(seed.id(), seed);
    }

    public ListingResponse create(CreateListingRequest r, String donorId) {
        String id = "listing-" + UUID.randomUUID();
        var listing = new ListingResponse(id, r.title(), r.description(), r.category(), r.quantity(), r.unit(), r.imageReference(), r.pickupArea(), r.collectionDeadline(), "AVAILABLE", donorId);
        listings.put(id, listing);
        return listing;
    }

    public List<ListingResponse> browse(String category, String pickupArea, String availability, String keyword) {
        return listings.values().stream().filter(l -> availability == null || l.state().equalsIgnoreCase(availability))
                .filter(l -> category == null || l.category().equalsIgnoreCase(category))
                .filter(l -> pickupArea == null || l.pickupArea().equalsIgnoreCase(pickupArea))
                .filter(l -> keyword == null || l.title().toLowerCase().contains(keyword.toLowerCase()) || (l.description() != null && l.description().toLowerCase().contains(keyword.toLowerCase())))
                .filter(l -> !l.collectionDeadline().isBefore(Instant.now()) || "EXPIRED".equals(l.state()))
                .collect(Collectors.toList());
    }

    public ListingResponse get(String id) {
        var l = listings.get(id);
        if (l == null) throw new ResourceNotFoundException("LISTING_NOT_FOUND", "Listing not found: " + id);
        return l;
    }
}
