package com.tesa.foodbridge.auth.api;

public record LoginRequest(
        String email,
        String password
) {}