package com.tesa.foodbridge.reservation.service;

import com.tesa.foodbridge.reservation.api.FoodRequestResponse;
import com.tesa.foodbridge.shared.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ReservationApiService {

    private final List<FoodRequestResponse> requests =
            new CopyOnWriteArrayList<>();

    public ReservationApiService() {

        requests.add(
                new FoodRequestResponse(
                        "request-001",
                        "listing-001",
                        "recipient1",
                        "Hope Community",
                        "PENDING",
                        Instant.now()
                )
        );
    }

    public List<FoodRequestResponse> findForListing(
            String listingId) {

        return requests.stream()
                .filter(request ->
                        request.listingId().equals(listingId))
                .toList();
    }

    public FoodRequestResponse get(String id) {

        return requests.stream()
                .filter(request ->
                        request.id().equals(id))
                .findFirst()
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "REQUEST_NOT_FOUND",
                                "Request not found: " + id
                        )
                );
    }
}