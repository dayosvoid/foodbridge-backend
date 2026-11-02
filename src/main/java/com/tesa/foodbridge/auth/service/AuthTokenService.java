package com.tesa.foodbridge.auth.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AuthTokenService {

    private static final long TOKEN_LIFETIME_SECONDS = 24 * 60 * 60;
    private static final String HMAC_ALGO = "HmacSHA256";

    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentMap<String, TokenEntry> tokens = new ConcurrentHashMap<>();
    private final Set<String> revokedTokens = ConcurrentHashMap.newKeySet();

    private final JdbcTemplate jdbc;
    private final byte[] signingKey;

    public AuthTokenService() {
        this(null, "foodbridge-durable-token-secret-2026");
    }

    @Autowired
    public AuthTokenService(
            @Autowired(required = false) JdbcTemplate jdbc,
            @Value("${auth.token.secret:${DB_PASSWORD:foodbridge-durable-token-secret-2026}}") String secret) {
        this.jdbc = jdbc;
        String effectiveSecret = (secret == null || secret.isBlank())
                ? "foodbridge-durable-token-secret-2026"
                : secret;
        this.signingKey = effectiveSecret.getBytes(StandardCharsets.UTF_8);
    }

    public String createToken(String username) {
        byte[] nonceBytes = new byte[16];
        secureRandom.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        long issuedAt = Instant.now().getEpochSecond();

        String payload = username + ":" + issuedAt + ":" + nonce;
        String encodedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(encodedPayload);
        String token = encodedPayload + "." + signature;

        Instant expiresAt = Instant.now().plusSeconds(TOKEN_LIFETIME_SECONDS);
        tokens.put(token, new TokenEntry(username, expiresAt));
        persistToken(token, username, expiresAt);

        return token;
    }

    public Optional<String> getUsername(String token) {
        if (token == null || token.isBlank() || revokedTokens.contains(token)) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        Instant newExpiry = now.plusSeconds(TOKEN_LIFETIME_SECONDS);

        TokenEntry cached = tokens.get(token);
        if (cached != null) {
            if (!now.isBefore(cached.expiresAt())) {
                tokens.remove(token, cached);
                return Optional.empty();
            }
            tokens.put(token, new TokenEntry(cached.username(), newExpiry));
            slideDatabaseExpiry(token, newExpiry);
            return Optional.of(cached.username());
        }

        Optional<TokenState> dbState = loadFromDatabase(token);
        if (dbState.isPresent()) {
            TokenState state = dbState.get();
            if (state.revoked() || !now.isBefore(state.expiresAt())) {
                return Optional.empty();
            }
            tokens.put(token, new TokenEntry(state.username(), newExpiry));
            slideDatabaseExpiry(token, newExpiry);
            return Optional.of(state.username());
        }

        Optional<String> verifiedUser = verifySignedToken(token, now);
        if (verifiedUser.isPresent()) {
            String username = verifiedUser.get();
            tokens.put(token, new TokenEntry(username, newExpiry));
            persistToken(token, username, newExpiry);
            return Optional.of(username);
        }

        return Optional.empty();
    }

    public void revokeToken(String token) {
        if (token != null && !token.isBlank()) {
            revokedTokens.add(token);
            tokens.remove(token);
            if (jdbc != null) {
                try {
                    jdbc.update("update auth_tokens set revoked = true where token = ?", token);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void clearMemoryCache() {
        tokens.clear();
    }

    private void persistToken(String token, String username, Instant expiresAt) {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update("""
                    insert into auth_tokens(token, username, expires_at, revoked)
                    values (?, ?, ?, false)
                    on conflict (token) do update
                    set expires_at = excluded.expires_at,
                        revoked = false
                    """,
                    token,
                    username,
                    Timestamp.from(expiresAt)
            );
        } catch (Exception ignored) {
        }
    }

    private void slideDatabaseExpiry(String token, Instant newExpiry) {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update(
                    "update auth_tokens set expires_at = ? where token = ? and revoked = false",
                    Timestamp.from(newExpiry),
                    token
            );
        } catch (Exception ignored) {
        }
    }

    private Optional<TokenState> loadFromDatabase(String token) {
        if (jdbc == null) {
            return Optional.empty();
        }
        try {
            return jdbc.query(
                    "select username, expires_at, revoked from auth_tokens where token = ?",
                    (rs, rowNum) -> new TokenState(
                            rs.getString("username"),
                            rs.getTimestamp("expires_at").toInstant(),
                            rs.getBoolean("revoked")
                    ),
                    token
            ).stream().findFirst();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<String> verifySignedToken(String token, Instant now) {
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            return Optional.empty();
        }
        String encodedPayload = token.substring(0, dot);
        String providedSig = token.substring(dot + 1);
        String expectedSig = sign(encodedPayload);

        if (!MessageDigest.isEqual(
                providedSig.getBytes(StandardCharsets.UTF_8),
                expectedSig.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }

        try {
            String payload = new String(
                    Base64.getUrlDecoder().decode(encodedPayload),
                    StandardCharsets.UTF_8
            );
            String[] parts = payload.split(":", 3);
            if (parts.length != 3 || parts[0].isBlank()) {
                return Optional.empty();
            }
            long issuedAt = Long.parseLong(parts[1]);
            Instant expiresAt = Instant.ofEpochSecond(issuedAt + TOKEN_LIFETIME_SECONDS);
            if (!now.isBefore(expiresAt)) {
                return Optional.empty();
            }
            return Optional.of(parts[0]);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGO));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign auth token", e);
        }
    }

    private record TokenEntry(
            String username,
            Instant expiresAt
    ) {}

    private record TokenState(
            String username,
            Instant expiresAt,
            boolean revoked
    ) {}
}
