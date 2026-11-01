package com.tesa.foodbridge.shared;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Converts the authenticated Spring Security user into the shared Actor
 * used by the application modules.
 */
@Component
public class ActorFactory {

    public Actor from(Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new RuleViolation("UNAUTHORIZED");
        }

        Actor.Role role = authentication.getAuthorities()
                .stream()
                .map(authority -> authority.getAuthority())
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority ->
                        Actor.Role.valueOf(authority.substring(5))
                )
                .findFirst()
                .orElseThrow(() ->
                        new RuleViolation("FORBIDDEN")
                );

        String username = authentication.getName();
        // Match UserProfileService: the in-memory admin1 login owns the seeded admin UUID.
        String identityKey = username.equals("admin1") ? "admin" : username;

        UUID id = UUID.nameUUIDFromBytes(
                ("foodbridge-simulated-" + identityKey)
                        .getBytes(StandardCharsets.UTF_8)
        );

        return new Actor(id, role);
    }
}
