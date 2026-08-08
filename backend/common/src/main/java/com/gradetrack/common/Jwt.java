package com.gradetrack.common;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Issues and verifies HMAC-signed JWTs for GradeTrack sessions.
 * Claims: sub = user_account.id, role = user_account.role.
 */
public final class Jwt {

    private static final String CLAIM_ROLE = "role";
    private static final long EXPIRY_HOURS = 12;

    private Jwt() {
    }

    public static String issue(String userId, String role) {
        Algorithm algorithm = Algorithm.HMAC256(secret());
        Instant now = Instant.now();
        return JWT.create()
                .withSubject(userId)
                .withClaim(CLAIM_ROLE, role)
                .withIssuedAt(now)
                .withExpiresAt(now.plus(EXPIRY_HOURS, ChronoUnit.HOURS))
                .sign(algorithm);
    }

    public static Optional<DecodedJWT> verify(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(secret());
            return Optional.of(JWT.require(algorithm).build().verify(token));
        } catch (JWTVerificationException e) {
            return Optional.empty();
        }
    }

    private static String secret() {
        String secret = System.getenv("JWT_SECRET");
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: JWT_SECRET");
        }
        return secret;
    }
}
