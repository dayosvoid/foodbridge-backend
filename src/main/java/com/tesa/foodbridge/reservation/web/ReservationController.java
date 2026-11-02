package com.tesa.foodbridge.reservation.web;

import com.tesa.foodbridge.reservation.api.FoodRequestResponse;
import com.tesa.foodbridge.reservation.api.ReservationOperations;
import com.tesa.foodbridge.shared.Actor;
import com.tesa.foodbridge.shared.ActorFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
            name = "bearerAuth"
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
            name = "bearerAuth"
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
            name = "bearerAuth"
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
            name = "bearerAuth"
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
            name = "bearerAuth"
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
            name = "bearerAuth"
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
            name = "bearerAuth"
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

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth"
    )
    @GetMapping("/admin/requests")
    public ResponseEntity<List<FoodRequestResponse>> adminRequests(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            Authentication authentication) {

        Actor actor = actorFactory.from(authentication);
        ReservationOperations.RequestPage page =
                reservations.browseAll(actor, status, offset, limit);

        List<FoodRequestResponse> body = page.items().stream()
                .map(this::response)
                .toList();

        return ResponseEntity.ok()
                .header("X-Total-Count", Long.toString(page.totalCount()))
                .body(body);
    }

    private FoodRequestResponse response(
            ReservationOperations.RequestView r) {

        return new FoodRequestResponse(
                r.id().toString(),
                r.listingId().toString(),
                r.requesterId().toString(),
                r.requesterName(),
                r.status(),
                r.createdAt(), r.expiredAt(), r.expirationReason()
        );
    }
}
