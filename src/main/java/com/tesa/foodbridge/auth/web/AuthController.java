
package com.tesa.foodbridge.auth.web;

import com.tesa.foodbridge.auth.api.LoginRequest;
import com.tesa.foodbridge.auth.api.LoginResponse;
import com.tesa.foodbridge.auth.api.MeResponse;
import com.tesa.foodbridge.auth.service.AuthTokenService;
import com.tesa.foodbridge.auth.service.UserProfileService;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Map<String, String> DEMO_EMAIL_TO_USERNAME =
            Map.of(
                    "donor1@demo.foodbridge.invalid", "donor1",
                    "recipient1@demo.foodbridge.invalid", "recipient1",
                    "admin@demo.foodbridge.invalid", "admin1"
            );

    private final AuthenticationManager authenticationManager;
    private final UserProfileService profiles;
    private final AuthTokenService tokenService;

    public AuthController(
            AuthenticationManager authenticationManager,
            UserProfileService profiles,
            AuthTokenService tokenService) {

        this.authenticationManager = authenticationManager;
        this.profiles = profiles;
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {

        if (request.email() == null || request.email().isBlank()
                || request.password() == null
                || request.password().isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Email and password are required"
            );
        }

        String email = request.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        String username = DEMO_EMAIL_TO_USERNAME.get(email);

        if (username == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid email or password"
            );
        }

        Authentication authentication =
                authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(
                                username,
                                request.password()
                        )
                );

        String authenticatedUsername = authentication.getName();

        MeResponse me = profiles.get(authenticatedUsername);

        String token = tokenService.createToken(authenticatedUsername);

        return new LoginResponse(
                token,
                me.id(),
                me.name(),
                me.role(),
                me.organisationName(),
                email
        );
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth"
    )
    @GetMapping("/me")
    public MeResponse me(Authentication authentication) {
        return profiles.get(authentication.getName());
    }
}
