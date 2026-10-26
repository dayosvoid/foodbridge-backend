package com.tesa.foodbridge.auth.api;

public record MeResponse(
        String id,
        String name,
        String role,
        String organisationName
) {}