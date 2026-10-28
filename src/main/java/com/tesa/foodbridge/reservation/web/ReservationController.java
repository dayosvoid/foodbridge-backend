package com.tesa.foodbridge.reservation.web;

import com.tesa.foodbridge.reservation.api.FoodRequestResponse;
import com.tesa.foodbridge.reservation.api.ReservationOperations;
import com.tesa.foodbridge.shared.Actor;
import com.tesa.foodbridge.shared.ActorFactory;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ReservationController {

    private final ReservationOperations reservations;
    private final ActorFactory actorFactory;

    public ReservationController(
            ReservationOperations reservations,
            ActorFactory actorFactory) {

        this.reservations = reservations;
        this.actorFactory = actorFactory;
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "basicAuth"
    )
    @PostMapping("/listings/{listingId}/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public FoodRequestResponse request(
            @PathVariable UUID listingId,
            @RequestHeader("Idempotency-Key") String key,
            Authentication authentication) {

        return response(
                reservations.request(
                        actorFactory.from(authentication),
                        listingId,
                        key
                )
        );
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "basicAuth"
    )
    @GetMapping("/listings/{listingId}/requests")
    public List<FoodRequestResponse> requests(
            @PathVariable UUID listingId,
            Authentication authentication) {

        Actor actor = actorFactory.from(authentication);

        return reservations
                .forListing(actor, listingId)
                .stream()
                .map(this::response)
                .toList();
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "basicAuth"
    )
    @GetMapping("/requests/{requestId}")
    public FoodRequestResponse get(
            @PathVariable UUID requestId,
            Authentication authentication) {

        return response(
                reservations.get(
                        actorFactory.from(authentication),
                        requestId
                )
        );
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "basicAuth"
    )
    @PatchMapping("/requests/{requestId}/approve")
    public FoodRequestResponse approve(
            @PathVariable UUID requestId,
            Authentication authentication) {

        return response(
                reservations.approve(
                        actorFactory.from(authentication),
                        requestId
                )
        );
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "basicAuth"
    )
    @PatchMapping("/requests/{requestId}/decline")
    public FoodRequestResponse decline(
            @PathVariable UUID requestId,
            Authentication authentication) {

        return response(
                reservations.decline(
                        actorFactory.from(authentication),
                        requestId
                )
        );
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "basicAuth"
    )
    @PostMapping("/requests/{requestId}/cancel")
    public FoodRequestResponse cancel(
            @PathVariable UUID requestId,
            Authentication authentication) {

        return response(
                reservations.cancel(
                        actorFactory.from(authentication),
                        requestId
                )
        );
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "basicAuth"
    )
    @GetMapping("/requests")
    public List<FoodRequestResponse> mine(
            @RequestParam(defaultValue = "false") boolean mine,
            Authentication authentication) {

        if (!mine) {
            throw new com.tesa.foodbridge.shared.RuleViolation(
                    "MINE_REQUIRED"
            );
        }

        Actor actor = actorFactory.from(authentication);

        return reservations.mine(actor)
                .stream()
                .map(this::response)
                .toList();
    }

    private FoodRequestResponse response(
            ReservationOperations.RequestView r) {

        return new FoodRequestResponse(
                r.id().toString(),
                r.listingId().toString(),
                r.requesterId().toString(),
                r.requesterName(),
                r.status(),
                r.createdAt()
        );
    }
}