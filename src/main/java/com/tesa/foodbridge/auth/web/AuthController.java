package com.tesa.foodbridge.auth.web;

import com.tesa.foodbridge.auth.api.LoginRequest;
import com.tesa.foodbridge.auth.api.LoginResponse;
import com.tesa.foodbridge.auth.api.MeResponse;
import com.tesa.foodbridge.auth.service.UserProfileService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserProfileService profiles;

    public AuthController(
            AuthenticationManager authenticationManager,
            UserProfileService profiles) {

        this.authenticationManager = authenticationManager;
        this.profiles = profiles;
    }

    @PostMapping("/login")
    public LoginResponse login(
            @RequestBody LoginRequest request) {

        Authentication authentication =
                authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(
                                request.username(),
                                request.password()
                        )
                );

        String username = authentication.getName();

        MeResponse me = profiles.get(username);

        String token = Base64.getEncoder().encodeToString(
                (request.username() + ":" + request.password())
                        .getBytes(StandardCharsets.UTF_8)
        );

        return new LoginResponse(
                token,
                me.id(),
                me.name(),
                me.role(),
                me.organisationName()
        );
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "basicAuth")
    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        return profiles.get(authentication.getName());
    }
}