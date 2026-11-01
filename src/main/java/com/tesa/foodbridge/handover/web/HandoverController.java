package com.tesa.foodbridge.handover.web;

import com.tesa.foodbridge.handover.api.HandoverOperations;
import com.tesa.foodbridge.shared.Actor;
import com.tesa.foodbridge.shared.ActorFactory;
import com.tesa.foodbridge.shared.RuleViolation;
import com.tesa.foodbridge.user.api.UserDirectory;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping
public class HandoverController {

    private final HandoverOperations handovers;
    private final ActorFactory actorFactory;
    private final UserDirectory users;

    public HandoverController(
            HandoverOperations handovers,
            ActorFactory actorFactory,
            UserDirectory users) {
        this.handovers = handovers;
        this.actorFactory = actorFactory;
        this.users = users;
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth")
    @PatchMapping("/api/requests/{requestId}/collected")
    public Map<String, String> complete(
            @PathVariable UUID requestId,
            Authentication authentication) {

        Actor actor = actorFactory.from(authentication);
        boolean admin = actor.role() == Actor.Role.ADMIN;

        if (!admin && actor.role() != Actor.Role.DONOR) {
            throw new RuleViolation("FORBIDDEN");
        }

        users.requireRole(actor.id(), actor.role());

        handovers.completeByDonorOrAdmin(
                requestId, actor.id(), admin);

        return Map.of(
                "message", "Food collection confirmed",
                "status", "COLLECTED"
        );
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth")
    @PatchMapping("/api/handovers/{handoverId}/reverse")
    public Map<String, String> reverse(
            @PathVariable UUID handoverId,
            Authentication authentication) {

        Actor actor = actorFactory.from(authentication);
        boolean admin = actor.role() == Actor.Role.ADMIN;

        if (!admin && actor.role() != Actor.Role.DONOR) {
            throw new RuleViolation("FORBIDDEN");
        }

        users.requireRole(actor.id(), actor.role());

        handovers.reverseByDonorOrAdmin(
                handoverId, actor.id(), admin);

        return Map.of(
                "message", "Handover reversed successfully",
                "status", "REVERSED"
        );
    }
}