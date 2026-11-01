
package com.tesa.foodbridge.auth.service;

import com.tesa.foodbridge.auth.api.MeResponse;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class UserProfileService {

    private final Map<String, MeResponse> profiles = Map.of(
            "donor1",
            new MeResponse(
                    com.tesa.foodbridge.demo.DemoSeed.id("donor1").toString(),
                    "Fresh Foods Bakery",
                    "DONOR",
                    "Fresh Foods Bakery",
                    "donor1@demo.foodbridge.invalid"
            ),

            "donor2",
            new MeResponse(
                    com.tesa.foodbridge.demo.DemoSeed.id("donor2").toString(),
                    "Surulere Neighbourhood Kitchen (Demo)",
                    "DONOR",
                    "Surulere Neighbourhood Kitchen (Demo)",
                    "donor2@demo.foodbridge.invalid"
            ),

            "recipient1",
            new MeResponse(
                    com.tesa.foodbridge.demo.DemoSeed.id("recipient1").toString(),
                    "Hope Community",
                    "RECIPIENT",
                    "Hope Community",
                    "recipient1@demo.foodbridge.invalid"
            ),

            "recipient2",
            new MeResponse(
                    com.tesa.foodbridge.demo.DemoSeed.id("recipient2").toString(),
                    "SIMULATED recipient2",
                    "RECIPIENT",
                    "SIMULATED recipient2",
                    "recipient2@demo.foodbridge.invalid"
            ),

            "recipient3",
            new MeResponse(
                    com.tesa.foodbridge.demo.DemoSeed.id("recipient3").toString(),
                    "SIMULATED recipient3",
                    "RECIPIENT",
                    "SIMULATED recipient3",
                    "recipient3@demo.foodbridge.invalid"
            ),

            "admin1",
            new MeResponse(
                    com.tesa.foodbridge.demo.DemoSeed.id("admin").toString(),
                    "FoodBridge Admin",
                    "ADMIN",
                    "FoodBridge",
                    "admin@demo.foodbridge.invalid"
            )
    );

    public MeResponse get(String username) {
        return profiles.getOrDefault(
                username,
                new MeResponse(
                        username,
                        username,
                        "USER",
                        username,
                        null
                )
        );
    }
}
