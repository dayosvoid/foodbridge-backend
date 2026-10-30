package com.tesa.foodbridge.handover.web;

import com.tesa.foodbridge.handover.api.HandoverOperations;
import com.tesa.foodbridge.shared.Actor;
import com.tesa.foodbridge.shared.ActorFactory;
import com.tesa.foodbridge.shared.RuleViolation;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/requests")
public class HandoverController {

    private final HandoverOperations handovers;
    private final ActorFactory actorFactory;

    public HandoverController(
            HandoverOperations handovers,
            ActorFactory actorFactory) {
        this.handovers = handovers;
        this.actorFactory = actorFactory;
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth"
    )
    @PatchMapping("/{requestId}/collected")
    public Map<String, String> complete(
            @PathVariable UUID requestId,
            Authentication authentication) {

        Actor actor = actorFactory.from(authentication);

        if (actor.role() != Actor.Role.RECIPIENT) {
            throw new RuleViolation("FORBIDDEN");
        }

        handovers.completeByRecipient(
                requestId,
                actor.id()
        );

        return Map.of(
                "message", "Food collection confirmed",
                "status", "COLLECTED"
        );
    }
}