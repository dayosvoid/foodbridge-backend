
package com.tesa.foodbridge.auth.api;

public record LoginResponse(
        String token,
        String id,
        String name,
        String role,
        String organisationName,
        String email
) {}
