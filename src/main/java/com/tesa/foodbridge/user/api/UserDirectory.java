package com.tesa.foodbridge.user.api;

import com.tesa.foodbridge.shared.Actor;
import java.util.UUID;

public interface UserDirectory {
    void requireRole(UUID id, Actor.Role role);
}
