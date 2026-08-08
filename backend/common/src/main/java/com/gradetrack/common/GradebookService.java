package com.gradetrack.common;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gradebook operations shared by the admin and teacher Lambdas: assignment
 * categories, assignments, and the two grade-write paths (which run through
 * AlertDetector). Each caller does its own request parsing and authorization —
 * this is just the DB work, kept in one place so both callers behave identically
 * and AlertDetector's dedup guard stays a single source of truth.
 */
public final class GradebookService {

    private GradebookService() {
    }

    public static APIGatewayResponse createCategory(String sectionId, String name, BigDecimal weightPct)
            throws SQLException {
        return insertReturningId(
                "INSERT INTO assignment_category (section_id, name, weight_pct) VALUES (?::uuid, ?, ?) RETURNING id",
                stmt -> {
                    stmt.setString(1, sectionId);
                    stmt.setString(2, name);
                    stmt.setBigDecimal(3, weightPct);
                });
    }

    public static APIGatewayResponse listCategories(String sectionId) throws SQLException {
        return queryList(
                "SELECT id, name, weight_pct FROM assignment_category WHERE section_id = ?::uuid ORDER BY name",
                "categories", stmt -> stmt.setString(1, sectionId), rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("name", rs.getString("name"));
                    row.put("weightPct", rs.getBigDecimal("weight_pct"));
                    return row;
                });
    }

    public static APIGatewayResponse updateCategory(String sectionId, String categoryId, String name,
                                                      BigDecimal weightPct) throws SQLException {
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE assignment_category SET name = COALESCE(?, name), "
                             + "weight_pct = COALESCE(?, weight_pct) WHERE id = ?::uuid AND section_id = ?::uuid")) {
            stmt.setString(1, name);
            bindNullable(stmt, 2, weightPct);
            stmt.setString(3, categoryId);
            stmt.setString(4, sectionId);
            executeUpdateOrNotFound(stmt, "Category not found");
        }
        return new APIGatewayResponse(200, Map.of("updated", true));
    }

    public static APIGatewayResponse deleteCategory(String sectionId, String categoryId) throws SQLException {
        return deleteById("DELETE FROM assignment_category WHERE id = ?::uuid AND section_id = ?::uuid",
                stmt -> {
                    stmt.setString(1, categoryId);
                    stmt.setString(2, sectionId);
                }, "Category not found");
    }

    public static APIGatewayResponse createAssignment(String sectionId, String categoryId, String name,
                                                        String description, BigDecimal pointsPossible,
                                                        String assignedDate, String dueDate) throws SQLException {
        return insertReturningId(
                """
                INSERT INTO assignment (section_id, category_id, name, description, points_possible,
                                         assigned_date, due_date)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::date, ?::date)
                RETURNING id
                """,
                stmt -> {
                    stmt.setString(1, sectionId);
                    stmt.setString(2, categoryId);
                    stmt.setString(3, name);
                    stmt.setString(4, description);
                    stmt.setBigDecimal(5, pointsPossible);
                    stmt.setString(6, assignedDate);
                    stmt.setString(7, dueDate);
                });
    }

    public static APIGatewayResponse listAssignments(String sectionId) throws SQLException {
        return queryList(
                """
                SELECT a.id, a.name, a.points_possible, a.due_date, ac.name AS category_name
                FROM assignment a
                JOIN assignment_category ac ON ac.id = a.category_id
                WHERE a.section_id = ?::uuid
                ORDER BY a.due_date
                """,
                "assignments", stmt -> stmt.setString(1, sectionId), rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("name", rs.getString("name"));
                    row.put("pointsPossible", rs.getBigDecimal("points_possible"));
                    row.put("dueDate", rs.getString("due_date"));
                    row.put("categoryName", rs.getString("category_name"));
                    return row;
                });
    }

    public static APIGatewayResponse updateAssignment(String sectionId, String assignmentId, String categoryId,
                                                        String name, String description, BigDecimal pointsPossible,
                                                        String assignedDate, String dueDate) throws SQLException {
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     """
                     UPDATE assignment SET category_id = COALESCE(?::uuid, category_id), name = COALESCE(?, name),
                            description = COALESCE(?, description),
                            points_possible = COALESCE(?, points_possible),
                            assigned_date = COALESCE(?::date, assigned_date), due_date = COALESCE(?::date, due_date)
                     WHERE id = ?::uuid AND section_id = ?::uuid
                     """)) {
            bindNullable(stmt, 1, categoryId);
            stmt.setString(2, name);
            stmt.setString(3, description);
            bindNullable(stmt, 4, pointsPossible);
            stmt.setString(5, assignedDate);
            stmt.setString(6, dueDate);
            stmt.setString(7, assignmentId);
            stmt.setString(8, sectionId);
            executeUpdateOrNotFound(stmt, "Assignment not found");
        }
        return new APIGatewayResponse(200, Map.of("updated", true));
    }

    public static APIGatewayResponse deleteAssignment(String sectionId, String assignmentId) throws SQLException {
        return deleteById("DELETE FROM assignment WHERE id = ?::uuid AND section_id = ?::uuid",
                stmt -> {
                    stmt.setString(1, assignmentId);
                    stmt.setString(2, sectionId);
                }, "Assignment not found");
    }

    public static APIGatewayResponse rosterForSection(String sectionId) throws SQLException {
        return queryList(
                """
                SELECT s.id, s.first_name, s.last_name
                FROM enrollment e
                JOIN student s ON s.id = e.student_id
                WHERE e.section_id = ?::uuid AND e.status = 'active'
                ORDER BY s.last_name
                """,
                "roster", stmt -> stmt.setString(1, sectionId), rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("firstName", rs.getString("first_name"));
                    row.put("lastName", rs.getString("last_name"));
                    return row;
                });
    }

    public static APIGatewayResponse upsertAssignmentGrade(String assignmentId, String studentId,
                                                             BigDecimal pointsEarned, String status,
                                                             String teacherComment) throws SQLException {
        String sql = """
                INSERT INTO assignment_grade (assignment_id, student_id, points_earned, status, graded_at,
                                               teacher_comment)
                VALUES (?::uuid, ?::uuid, ?, ?, now(), ?)
                ON CONFLICT (assignment_id, student_id) DO UPDATE SET
                    points_earned = EXCLUDED.points_earned,
                    status = EXCLUDED.status,
                    graded_at = now(),
                    teacher_comment = EXCLUDED.teacher_comment
                RETURNING id
                """;

        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, assignmentId);
            stmt.setString(2, studentId);
            bindNullable(stmt, 3, pointsEarned);
            stmt.setString(4, status);
            stmt.setString(5, teacherComment);

            String gradeId;
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                gradeId = rs.getString("id");
            }
            if ("missing".equals(status)) {
                AlertDetector.checkMissingAssignment(conn, assignmentId, gradeId, studentId);
            }
            return new APIGatewayResponse(201, Map.of("id", gradeId));
        }
    }

    public static APIGatewayResponse upsertTermGrade(String sectionId, String termId, String studentId,
                                                       BigDecimal finalPct, String finalLetter, BigDecimal gpaPoints,
                                                       boolean posted) throws SQLException {
        String sql = """
                INSERT INTO term_grade (student_id, section_id, term_id, final_pct, final_letter, gpa_points,
                                         posted_at)
                VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, CASE WHEN ? THEN now() ELSE NULL END)
                ON CONFLICT (student_id, section_id, term_id) DO UPDATE SET
                    final_pct = EXCLUDED.final_pct,
                    final_letter = EXCLUDED.final_letter,
                    gpa_points = EXCLUDED.gpa_points,
                    posted_at = CASE WHEN ? THEN now() ELSE term_grade.posted_at END
                RETURNING id
                """;

        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, studentId);
            stmt.setString(2, sectionId);
            stmt.setString(3, termId);
            bindNullable(stmt, 4, finalPct);
            stmt.setString(5, finalLetter);
            bindNullable(stmt, 6, gpaPoints);
            stmt.setBoolean(7, posted);
            stmt.setBoolean(8, posted);

            String termGradeId;
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                termGradeId = rs.getString("id");
            }
            AlertDetector.checkTermGrade(conn, sectionId, termId, termGradeId, studentId, finalPct, finalLetter,
                    posted);
            return new APIGatewayResponse(201, Map.of("id", termGradeId));
        }
    }

    /** Plain (statusCode, body) pair — callers wrap this in their own ApiResponses shape. */
    public record APIGatewayResponse(int statusCode, Object body) {
    }

    private static APIGatewayResponse insertReturningId(String sql, ParamBinder binder) throws SQLException {
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            binder.bind(stmt);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return new APIGatewayResponse(201, Map.of("id", rs.getString("id")));
            }
        }
    }

    private static APIGatewayResponse queryList(String sql, String rootKey, ParamBinder binder, RowMapper mapper)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            binder.bind(stmt);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
            }
        }
        return new APIGatewayResponse(200, Map.of(rootKey, rows));
    }

    private static void bindNullable(PreparedStatement stmt, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, Types.NUMERIC);
        } else {
            stmt.setBigDecimal(index, value);
        }
    }

    private static void bindNullable(PreparedStatement stmt, int index, String value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, Types.OTHER);
        } else {
            stmt.setString(index, value);
        }
    }

    /** 404s if the update touched zero rows (id doesn't exist, or belongs to a different section). */
    private static void executeUpdateOrNotFound(PreparedStatement stmt, String notFoundMessage) throws SQLException {
        if (stmt.executeUpdate() == 0) {
            throw ApiException.notFound(notFoundMessage);
        }
    }

    /**
     * Runs a DELETE. 404 if it touched zero rows; 409 (not a raw 500) if Postgres rejects it
     * with a foreign-key violation because other records still reference this row.
     */
    private static APIGatewayResponse deleteById(String sql, ParamBinder binder, String notFoundMessage)
            throws SQLException {
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            binder.bind(stmt);
            try {
                executeUpdateOrNotFound(stmt, notFoundMessage);
            } catch (SQLException e) {
                if ("23503".equals(e.getSQLState())) {
                    throw ApiException.conflict("Cannot delete: other records still reference this");
                }
                throw e;
            }
        }
        return new APIGatewayResponse(200, Map.of("deleted", true));
    }

    private interface ParamBinder {
        void bind(PreparedStatement stmt) throws SQLException;
    }

    private interface RowMapper {
        Map<String, Object> map(ResultSet rs) throws SQLException;
    }
}
