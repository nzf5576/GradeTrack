package com.gradetrack.common;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.util.Map;
import java.util.Optional;

/** Extracts and verifies the caller's JWT from the Authorization header. */
public final class AuthContext {

    private static final String BEARER_PREFIX = "Bearer ";

    private AuthContext() {
    }

    public record AuthenticatedUser(String userId, String role) {
    }

    public static AuthenticatedUser requireUser(APIGatewayProxyRequestEvent request) {
        String header = authorizationHeader(request);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw ApiException.unauthorized("Missing bearer token");
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        DecodedJWT decoded = Jwt.verify(token)
                .orElseThrow(() -> ApiException.unauthorized("Invalid or expired token"));

        return new AuthenticatedUser(decoded.getSubject(), decoded.getClaim("role").asString());
    }

    public static AuthenticatedUser requireAdmin(APIGatewayProxyRequestEvent request) {
        AuthenticatedUser user = requireUser(request);
        if (!"admin".equals(user.role())) {
            throw ApiException.forbidden("Admin access required");
        }
        return user;
    }

    public static AuthenticatedUser requireTeacher(APIGatewayProxyRequestEvent request) {
        AuthenticatedUser user = requireUser(request);
        if (!"teacher".equals(user.role())) {
            throw ApiException.forbidden("Teacher access required");
        }
        return user;
    }

    private static String authorizationHeader(APIGatewayProxyRequestEvent request) {
        Map<String, String> headers = request.getHeaders();
        if (headers == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase("Authorization"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
