package com.gradetrack.students;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.gradetrack.common.ApiException;
import com.gradetrack.common.ApiResponses;
import com.gradetrack.common.AuthContext;
import com.gradetrack.common.AuthContext.AuthenticatedUser;
import com.gradetrack.common.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles GET /students and GET /students/{id}/grades behind a single proxy
 * integration, same one-Lambda-per-bounded-context shape as AuthHandler.
 * CORS preflight is handled by API Gateway now, so there's no OPTIONS branch here.
 */
public class StudentsHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Pattern GRADES_PATH = Pattern.compile("^.*/students/([^/]+)/grades/?$");
    private static final Pattern STUDENTS_PATH = Pattern.compile("^.*/students/?$");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        String method = request.getHttpMethod();
        String path = request.getPath() == null ? "" : request.getPath();

        try {
            AuthenticatedUser user = AuthContext.requireUser(request);

            if ("GET".equalsIgnoreCase(method)) {
                Matcher gradesMatch = GRADES_PATH.matcher(path);
                if (gradesMatch.matches()) {
                    return studentGrades(user, gradesMatch.group(1));
                }
                if (STUDENTS_PATH.matcher(path).matches()) {
                    return listStudents(user);
                }
            }
            return ApiResponses.notFoundResponse();
        } catch (ApiException e) {
            return ApiResponses.error(e);
        } catch (Exception e) {
            context.getLogger().log("Unhandled error in StudentsHandler: " + e);
            return ApiResponses.internalError();
        }
    }

    private APIGatewayProxyResponseEvent listStudents(AuthenticatedUser user) throws SQLException {
        String sql = """
                SELECT s.id, s.first_name, s.last_name, sch.name AS school_name
                FROM guardian_student gs
                JOIN student s ON s.id = gs.student_id
                LEFT JOIN school sch ON sch.id = s.current_school_id
                WHERE gs.guardian_id = ?::uuid
                ORDER BY s.first_name
                """;

        List<Map<String, Object>> students = new ArrayList<>();
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, user.userId());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("firstName", rs.getString("first_name"));
                    row.put("lastName", rs.getString("last_name"));
                    row.put("schoolName", rs.getString("school_name"));
                    students.add(row);
                }
            }
        }
        return ApiResponses.ok(Map.of("students", students));
    }

    private APIGatewayProxyResponseEvent studentGrades(AuthenticatedUser user, String studentId) throws SQLException {
        requireGuardianOf(user, studentId);

        String sql = """
                SELECT c.name AS course_name, t.name AS term_name,
                       tr.first_name AS teacher_first_name, tr.last_name AS teacher_last_name,
                       tg.final_pct, tg.final_letter, tg.posted_at
                FROM term_grade tg
                JOIN section sec ON sec.id = tg.section_id
                JOIN course c ON c.id = sec.course_id
                JOIN teacher tr ON tr.id = sec.teacher_id
                JOIN term t ON t.id = tg.term_id
                WHERE tg.student_id = ?::uuid
                ORDER BY t.start_date DESC, c.name
                """;

        List<Map<String, Object>> grades = new ArrayList<>();
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, studentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("courseName", rs.getString("course_name"));
                    row.put("termName", rs.getString("term_name"));
                    row.put("teacherName", rs.getString("teacher_first_name") + " " + rs.getString("teacher_last_name"));
                    row.put("finalPct", rs.getBigDecimal("final_pct"));
                    row.put("finalLetter", rs.getString("final_letter"));
                    grades.add(row);
                }
            }
        }
        return ApiResponses.ok(Map.of("grades", grades));
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
}
