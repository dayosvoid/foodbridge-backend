
package com.tesa.foodbridge.auth.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AuthTokenService {

    private static final long TOKEN_LIFETIME_SECONDS = 30 * 60;

    private final SecureRandom secureRandom = new SecureRandom();

    private final ConcurrentMap<String, TokenEntry> tokens =
            new ConcurrentHashMap<>();

    public String createToken(String username) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);

        String token = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(randomBytes);

        Instant expiresAt = Instant.now()
                .plusSeconds(TOKEN_LIFETIME_SECONDS);

        tokens.put(token, new TokenEntry(username, expiresAt));

        return token;
    }

    public Optional<String> getUsername(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        TokenEntry entry = tokens.get(token);

        if (entry == null) {
            return Optional.empty();
        }

        if (!Instant.now().isBefore(entry.expiresAt())) {
            tokens.remove(token, entry);
            return Optional.empty();
        }

        return Optional.of(entry.username());
    }

    public void revokeToken(String token) {
        if (token != null && !token.isBlank()) {
            tokens.remove(token);
        }
    }

    private record TokenEntry(
            String username,
            Instant expiresAt
    ) {}
}
