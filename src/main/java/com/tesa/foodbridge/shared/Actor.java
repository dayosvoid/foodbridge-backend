package com.tesa.foodbridge.shared;

import java.util.Objects;
import java.util.UUID;

/** Trusted identity supplied by the security adapter, never deserialized from a request body. */
public record Actor(UUID id, Role role) {
    public Actor { Objects.requireNonNull(id); Objects.requireNonNull(role); }
    public enum Role { DONOR, RECIPIENT, ADMIN }
}
