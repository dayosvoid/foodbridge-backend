package com.tesa.foodbridge.auth.service;

import com.tesa.foodbridge.auth.api.MeResponse;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class UserProfileService {

    private final Map<String, MeResponse> profiles = Map.of(
            "donor1",
            new MeResponse(
                    "donor1",
                    "Fresh Foods Bakery",
                    "DONOR",
                    "Fresh Foods Bakery"
            ),

            "recipient1",
            new MeResponse(
                    "recipient1",
                    "Hope Community",
                    "RECIPIENT",
                    "Hope Community"
            ),

            "admin1",
            new MeResponse(
                    "admin1",
                    "FoodBridge Admin",
                    "ADMIN",
                    "FoodBridge"
            )
    );

    public MeResponse get(String username) {
        return profiles.getOrDefault(
                username,
                new MeResponse(
                        username,
                        username,
                        "USER",
                        username
                )
        );
    }
}