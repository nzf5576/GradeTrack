package com.gradetrack.alerts;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.gradetrack.common.ApiException;
import com.gradetrack.common.ApiResponses;
import com.gradetrack.common.AuthContext;
import com.gradetrack.common.AuthContext.AuthenticatedUser;
import com.gradetrack.common.Db;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles alert rule CRUD and notification read/mark-read behind a single
 * proxy integration, same pattern as AuthHandler/StudentsHandler. There is
 * deliberately no detection engine here yet — nothing creates notification
 * rows except the dev seed script; this is just the parent-facing surface.
 */
public class AlertsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Set<String> TRIGGER_TYPES = Set.of(
            "grade_below", "missing_assignment", "grade_drop_pct", "report_card_posted");
    private static final Set<String> CHANNELS = Set.of("email", "sms", "push", "in_app");

    private static final Pattern RULES_PATH = Pattern.compile("^.*/alerts/rules/?$");
    private static final Pattern RULE_ITEM_PATH = Pattern.compile("^.*/alerts/rules/([^/]+)/?$");
    private static final Pattern NOTIFICATIONS_PATH = Pattern.compile("^.*/alerts/notifications/?$");
    private static final Pattern NOTIFICATION_READ_PATH = Pattern.compile("^.*/alerts/notifications/([^/]+)/read/?$");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        String method = request.getHttpMethod();
        String path = request.getPath() == null ? "" : request.getPath();

        try {
            AuthenticatedUser user = AuthContext.requireUser(request);

            Matcher readMatch = NOTIFICATION_READ_PATH.matcher(path);
            if ("POST".equalsIgnoreCase(method) && readMatch.matches()) {
                return markNotificationRead(user, readMatch.group(1));
            }
            if ("GET".equalsIgnoreCase(method) && NOTIFICATIONS_PATH.matcher(path).matches()) {
                return listNotifications(user);
            }

            Matcher ruleItemMatch = RULE_ITEM_PATH.matcher(path);
            if ("DELETE".equalsIgnoreCase(method) && ruleItemMatch.matches()) {
                return deleteRule(user, ruleItemMatch.group(1));
            }
            if ("GET".equalsIgnoreCase(method) && RULES_PATH.matcher(path).matches()) {
                return listRules(user);
            }
            if ("POST".equalsIgnoreCase(method) && RULES_PATH.matcher(path).matches()) {
                return createRule(user, request);
            }

            return ApiResponses.notFoundResponse();
        } catch (ApiException e) {
            return ApiResponses.error(e);
        } catch (Exception e) {
            context.getLogger().log("Unhandled error in AlertsHandler: " + e);
            return ApiResponses.internalError();
        }
    }

    private APIGatewayProxyResponseEvent listRules(AuthenticatedUser user) throws SQLException {
        String sql = """
                SELECT id, student_id, trigger_type, threshold_value, channel, active
                FROM alert_rule
                WHERE guardian_id = ?::uuid
                ORDER BY id
                """;

        List<Map<String, Object>> rules = new ArrayList<>();
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.userId());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("studentId", rs.getString("student_id"));
                    row.put("triggerType", rs.getString("trigger_type"));
                    row.put("thresholdValue", rs.getBigDecimal("threshold_value"));
                    row.put("channel", rs.getString("channel"));
                    row.put("active", rs.getBoolean("active"));
                    rules.add(row);
                }
            }
        }
        return ApiResponses.ok(Map.of("rules", rules));
    }

    private APIGatewayProxyResponseEvent createRule(AuthenticatedUser user, APIGatewayProxyRequestEvent request)
            throws SQLException {
        CreateAlertRuleRequest body = parseBody(request);
        validateCreateRule(body);

        if (body.studentId() != null) {
            requireGuardianOf(user, body.studentId());
        }

        String sql = """
                INSERT INTO alert_rule (guardian_id, student_id, trigger_type, threshold_value, channel, active)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, true)
                RETURNING id
                """;

        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.userId());
            if (body.studentId() != null) {
                stmt.setString(2, body.studentId());
            } else {
                stmt.setNull(2, Types.OTHER);
            }
            stmt.setString(3, body.triggerType());
            if (body.thresholdValue() != null) {
                stmt.setBigDecimal(4, body.thresholdValue());
            } else {
                stmt.setNull(4, Types.NUMERIC);
            }
            stmt.setString(5, body.channel());

            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return ApiResponses.created(Map.of("id", rs.getString("id")));
            }
        }
    }

    private APIGatewayProxyResponseEvent deleteRule(AuthenticatedUser user, String ruleId) throws SQLException {
        String sql = "DELETE FROM alert_rule WHERE id = ?::uuid AND guardian_id = ?::uuid";

        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, ruleId);
            stmt.setString(2, user.userId());
            int updated = executeUpdateOrNotFound(stmt, "Alert rule not found");
            return ApiResponses.ok(Map.of("deleted", updated));
        }
    }

    private APIGatewayProxyResponseEvent listNotifications(AuthenticatedUser user) throws SQLException {
        String sql = """
                SELECT id, message, related_entity_type, related_entity_id, read_at, created_at
                FROM notification
                WHERE user_account_id = ?::uuid
                ORDER BY created_at DESC
                """;

        List<Map<String, Object>> notifications = new ArrayList<>();
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.userId());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("message", rs.getString("message"));
                    row.put("relatedEntityType", rs.getString("related_entity_type"));
                    row.put("relatedEntityId", rs.getString("related_entity_id"));
                    Timestamp readAt = rs.getTimestamp("read_at");
                    row.put("readAt", readAt == null ? null : readAt.toInstant().toString());
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    notifications.add(row);
                }
            }
        }
        return ApiResponses.ok(Map.of("notifications", notifications));
    }

    private APIGatewayProxyResponseEvent markNotificationRead(AuthenticatedUser user, String notificationId)
            throws SQLException {
        String sql = "UPDATE notification SET read_at = now() WHERE id = ?::uuid AND user_account_id = ?::uuid";

        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, notificationId);
            stmt.setString(2, user.userId());
            executeUpdateOrNotFound(stmt, "Notification not found");
            return ApiResponses.ok(Map.of("read", true));
        }
    }

    /** Runs an UPDATE/DELETE, 404s if it touched zero rows (covers "not found" and "not yours"). */
    private int executeUpdateOrNotFound(PreparedStatement stmt, String notFoundMessage) throws SQLException {
        int updated = stmt.executeUpdate();
        if (updated == 0) {
            throw ApiException.notFound(notFoundMessage);
        }
        return updated;
    }

    /** 404 (not 403) when the caller isn't linked to this student, so we don't confirm the id exists. */
    private void requireGuardianOf(AuthenticatedUser user, String studentId) throws SQLException {
        String sql = "SELECT 1 FROM guardian_student WHERE guardian_id = ?::uuid AND student_id = ?::uuid";

        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.userId());
            stmt.setString(2, studentId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw ApiException.notFound("Student not found");
                }
            }
        } catch (SQLException e) {
            if ("22P02".equals(e.getSQLState())) { // invalid_text_representation, e.g. malformed uuid
                throw ApiException.notFound("Student not found");
            }
            throw e;
        }
    }

    private void validateCreateRule(CreateAlertRuleRequest body) {
        if (body.triggerType() == null || !TRIGGER_TYPES.contains(body.triggerType())) {
            throw ApiException.badRequest("triggerType must be one of " + TRIGGER_TYPES);
        }
        if (body.channel() == null || !CHANNELS.contains(body.channel())) {
            throw ApiException.badRequest("channel must be one of " + CHANNELS);
        }
    }

    private CreateAlertRuleRequest parseBody(APIGatewayProxyRequestEvent request) {
        String rawBody = request.getBody();
        if (rawBody == null || rawBody.isBlank()) {
            throw ApiException.badRequest("Request body is required");
        }
        try {
            return ApiResponses.mapper().readValue(rawBody, CreateAlertRuleRequest.class);
        } catch (Exception e) {
            throw ApiException.badRequest("Malformed request body");
        }
    }

    private record CreateAlertRuleRequest(String studentId, String triggerType, BigDecimal thresholdValue,
                                           String channel) {
    }
}
