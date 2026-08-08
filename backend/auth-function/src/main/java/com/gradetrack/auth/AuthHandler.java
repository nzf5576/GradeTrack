package com.gradetrack.auth;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.gradetrack.common.ApiException;
import com.gradetrack.common.ApiResponses;
import com.gradetrack.common.Db;
import com.gradetrack.common.Jwt;
import com.gradetrack.common.PasswordHasher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Handles POST /auth/signup and POST /auth/login behind a single API Gateway
 * proxy integration. One Lambda per bounded context (auth, students, grades, ...)
 * keeps Java cold starts manageable versus one Lambda per endpoint.
 */
public class AuthHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MIN_PASSWORD_LENGTH = 8;

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        String method = request.getHttpMethod();
        String path = request.getPath() == null ? "" : request.getPath();

        if ("OPTIONS".equalsIgnoreCase(method)) {
            return ApiResponses.ok(Map.of());
        }

        try {
            if ("POST".equalsIgnoreCase(method) && path.endsWith("/auth/signup")) {
                return signup(request);
            }
            if ("POST".equalsIgnoreCase(method) && path.endsWith("/auth/login")) {
                return login(request);
            }
            return ApiResponses.notFoundResponse();
        } catch (ApiException e) {
            return ApiResponses.error(e);
        } catch (Exception e) {
            context.getLogger().log("Unhandled error in AuthHandler: " + e);
            return ApiResponses.internalError();
        }
    }

    private APIGatewayProxyResponseEvent signup(APIGatewayProxyRequestEvent request) throws SQLException {
        SignupRequest body = parseBody(request, SignupRequest.class);
        validateSignup(body);

        String passwordHash = PasswordHasher.hash(body.password());

        String sql = """
                INSERT INTO user_account (email, password_hash, first_name, last_name, role)
                VALUES (?, ?, ?, ?, 'parent')
                RETURNING id
                """;

        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, body.email().toLowerCase());
            stmt.setString(2, passwordHash);
            stmt.setString(3, body.firstName());
            stmt.setString(4, body.lastName());

            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                String userId = rs.getString("id");
                String token = Jwt.issue(userId, "parent");
                return ApiResponses.created(Map.of(
                        "userId", userId,
                        "token", token
                ));
            }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) { // unique_violation on email
                throw ApiException.conflict("An account with that email already exists");
            }
            throw e;
        }
    }

    private APIGatewayProxyResponseEvent login(APIGatewayProxyRequestEvent request) throws SQLException {
        LoginRequest body = parseBody(request, LoginRequest.class);
        if (isBlank(body.email()) || isBlank(body.password())) {
            throw ApiException.badRequest("Email and password are required");
        }

        String sql = "SELECT id, password_hash, role FROM user_account WHERE email = ?";

        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, body.email().toLowerCase());

            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw ApiException.unauthorized("Invalid email or password");
                }
                String storedHash = rs.getString("password_hash");
                if (storedHash == null || !PasswordHasher.matches(body.password(), storedHash)) {
                    throw ApiException.unauthorized("Invalid email or password");
                }
                String userId = rs.getString("id");
                String role = rs.getString("role");
                String token = Jwt.issue(userId, role);
                return ApiResponses.ok(Map.of(
                        "userId", userId,
                        "role", role,
                        "token", token
                ));
            }
        }
    }

    private void validateSignup(SignupRequest body) {
        if (isBlank(body.email()) || !EMAIL_PATTERN.matcher(body.email()).matches()) {
            throw ApiException.badRequest("A valid email is required");
        }
        if (isBlank(body.password()) || body.password().length() < MIN_PASSWORD_LENGTH) {
            throw ApiException.badRequest("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (isBlank(body.firstName()) || isBlank(body.lastName())) {
            throw ApiException.badRequest("First and last name are required");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private <T> T parseBody(APIGatewayProxyRequestEvent request, Class<T> type) {
        String rawBody = request.getBody();
        if (isBlank(rawBody)) {
            throw ApiException.badRequest("Request body is required");
        }
        try {
            return ApiResponses.mapper().readValue(rawBody, type);
        } catch (Exception e) {
            throw ApiException.badRequest("Malformed request body");
        }
    }

    private record SignupRequest(String email, String password, String firstName, String lastName) {
    }

    private record LoginRequest(String email, String password) {
    }
}
