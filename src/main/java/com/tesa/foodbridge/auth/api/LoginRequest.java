package com.tesa.foodbridge.auth.api;

public record LoginRequest(
        String username,
        String password
) {}