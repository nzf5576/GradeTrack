package com.gradetrack.teacher;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.gradetrack.common.ApiException;
import com.gradetrack.common.ApiResponses;
import com.gradetrack.common.AuthContext;
import com.gradetrack.common.AuthContext.AuthenticatedUser;
import com.gradetrack.common.Db;
import com.gradetrack.common.GradebookService;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Teacher-facing gradebook: a scoped-down mirror of AdminHandler's gradebook routes
 * (same GradebookService underneath, so behavior — including AlertDetector — matches
 * exactly) restricted to sections this teacher actually teaches. Every route
 * verifies ownership before touching anything: 404 (not 403) if a section/assignment
 * isn't theirs, same "don't confirm existence" pattern used everywhere else.
 */
public class TeacherHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Set<String> ASSIGNMENT_GRADE_STATUSES =
            Set.of("graded", "missing", "late", "excused", "pending");

    private static final Pattern SECTIONS_PATH = Pattern.compile("^.*/teacher/sections/?$");
    private static final Pattern SECTION_ROSTER_PATH =
            Pattern.compile("^.*/teacher/sections/([^/]+)/roster/?$");
    private static final Pattern SECTION_CATEGORIES_PATH =
            Pattern.compile("^.*/teacher/sections/([^/]+)/categories/?$");
    private static final Pattern SECTION_CATEGORY_ITEM_PATH =
            Pattern.compile("^.*/teacher/sections/([^/]+)/categories/([^/]+)/?$");
    private static final Pattern SECTION_ASSIGNMENTS_PATH =
            Pattern.compile("^.*/teacher/sections/([^/]+)/assignments/?$");
    private static final Pattern SECTION_ASSIGNMENT_ITEM_PATH =
            Pattern.compile("^.*/teacher/sections/([^/]+)/assignments/([^/]+)/?$");
    private static final Pattern ASSIGNMENT_GRADE_PATH =
            Pattern.compile("^.*/teacher/assignments/([^/]+)/grades/([^/]+)/?$");
    private static final Pattern SECTION_TERM_GRADE_PATH =
            Pattern.compile("^.*/teacher/sections/([^/]+)/term-grades/([^/]+)/?$");

    private final List<Route> routes = List.of(
            new Route("GET", SECTIONS_PATH, this::listMySections),
            new Route("GET", SECTION_ROSTER_PATH, this::roster),
            new Route("GET", SECTION_CATEGORIES_PATH, this::listCategories),
            new Route("POST", SECTION_CATEGORIES_PATH, this::createCategory),
            new Route("PUT", SECTION_CATEGORY_ITEM_PATH, this::updateCategory),
            new Route("DELETE", SECTION_CATEGORY_ITEM_PATH, this::deleteCategory),
            new Route("GET", SECTION_ASSIGNMENTS_PATH, this::listAssignments),
            new Route("POST", SECTION_ASSIGNMENTS_PATH, this::createAssignment),
            new Route("PUT", SECTION_ASSIGNMENT_ITEM_PATH, this::updateAssignment),
            new Route("DELETE", SECTION_ASSIGNMENT_ITEM_PATH, this::deleteAssignment),
            new Route("PUT", ASSIGNMENT_GRADE_PATH, this::upsertAssignmentGrade),
            new Route("PUT", SECTION_TERM_GRADE_PATH, this::upsertTermGrade)
    );

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent request, Context context) {
        String method = request.getHttpMethod();
        String path = request.getPath() == null ? "" : request.getPath();

        try {
            AuthenticatedUser user = AuthContext.requireTeacher(request);
            String teacherId = resolveTeacherId(user.userId());

            for (Route route : routes) {
                Matcher matcher = route.pattern().matcher(path);
                if (route.method().equalsIgnoreCase(method) && matcher.matches()) {
                    return route.handler().handle(request, matcher, teacherId);
                }
            }
            return ApiResponses.notFoundResponse();
        } catch (ApiException e) {
            return ApiResponses.error(e);
        } catch (Exception e) {
            context.getLogger().log("Unhandled error in TeacherHandler: " + e);
            return ApiResponses.internalError();
        }
    }

    // ---- Sections ----

    private APIGatewayProxyResponseEvent listMySections(APIGatewayProxyRequestEvent request, Matcher m,
                                                          String teacherId) throws SQLException {
        String sql = """
                SELECT sec.id, c.name AS course_name, t.id AS term_id, t.name AS term_name, sec.period, sec.room
                FROM section sec
                JOIN course c ON c.id = sec.course_id
                JOIN term t ON t.id = sec.term_id
                WHERE sec.teacher_id = ?::uuid
                ORDER BY c.name
                """;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, teacherId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("courseName", rs.getString("course_name"));
                    row.put("termId", rs.getString("term_id"));
                    row.put("termName", rs.getString("term_name"));
                    row.put("period", rs.getString("period"));
                    row.put("room", rs.getString("room"));
                    rows.add(row);
                }
            }
        }
        return ApiResponses.ok(Map.of("sections", rows));
    }

    private APIGatewayProxyResponseEvent roster(APIGatewayProxyRequestEvent request, Matcher m, String teacherId)
            throws SQLException {
        String sectionId = m.group(1);
        requireOwnedSection(teacherId, sectionId);
        return wrap(GradebookService.rosterForSection(sectionId));
    }

    // ---- Assignment categories ----

    private APIGatewayProxyResponseEvent listCategories(APIGatewayProxyRequestEvent request, Matcher m,
                                                          String teacherId) throws SQLException {
        String sectionId = m.group(1);
        requireOwnedSection(teacherId, sectionId);
        return wrap(GradebookService.listCategories(sectionId));
    }

    private APIGatewayProxyResponseEvent createCategory(APIGatewayProxyRequestEvent request, Matcher m,
                                                          String teacherId) throws SQLException {
        String sectionId = m.group(1);
        requireOwnedSection(teacherId, sectionId);
        CreateCategoryRequest body = parseBody(request, CreateCategoryRequest.class);
        if (isBlank(body.name()) || body.weightPct() == null) {
            throw ApiException.badRequest("name and weightPct are required");
        }
        return wrap(GradebookService.createCategory(sectionId, body.name(), body.weightPct()));
    }

    private APIGatewayProxyResponseEvent updateCategory(APIGatewayProxyRequestEvent request, Matcher m,
                                                          String teacherId) throws SQLException {
        String sectionId = m.group(1);
        requireOwnedSection(teacherId, sectionId);
        UpdateCategoryRequest body = parseBody(request, UpdateCategoryRequest.class);
        return wrap(GradebookService.updateCategory(sectionId, m.group(2), body.name(), body.weightPct()));
    }

    private APIGatewayProxyResponseEvent deleteCategory(APIGatewayProxyRequestEvent request, Matcher m,
                                                          String teacherId) throws SQLException {
        String sectionId = m.group(1);
        requireOwnedSection(teacherId, sectionId);
        return wrap(GradebookService.deleteCategory(sectionId, m.group(2)));
    }

    // ---- Assignments ----

    private APIGatewayProxyResponseEvent listAssignments(APIGatewayProxyRequestEvent request, Matcher m,
                                                           String teacherId) throws SQLException {
        String sectionId = m.group(1);
        requireOwnedSection(teacherId, sectionId);
        return wrap(GradebookService.listAssignments(sectionId));
    }

    private APIGatewayProxyResponseEvent createAssignment(APIGatewayProxyRequestEvent request, Matcher m,
                                                            String teacherId) throws SQLException {
        String sectionId = m.group(1);
        requireOwnedSection(teacherId, sectionId);
        CreateAssignmentRequest body = parseBody(request, CreateAssignmentRequest.class);
        if (isBlank(body.categoryId()) || isBlank(body.name()) || body.pointsPossible() == null) {
            throw ApiException.badRequest("categoryId, name, and pointsPossible are required");
        }
        return wrap(GradebookService.createAssignment(sectionId, body.categoryId(), body.name(),
                body.description(), body.pointsPossible(), body.assignedDate(), body.dueDate()));
    }

    private APIGatewayProxyResponseEvent updateAssignment(APIGatewayProxyRequestEvent request, Matcher m,
                                                            String teacherId) throws SQLException {
        String sectionId = m.group(1);
        requireOwnedSection(teacherId, sectionId);
        UpdateAssignmentRequest body = parseBody(request, UpdateAssignmentRequest.class);
        return wrap(GradebookService.updateAssignment(sectionId, m.group(2), body.categoryId(), body.name(),
                body.description(), body.pointsPossible(), body.assignedDate(), body.dueDate()));
    }

    private APIGatewayProxyResponseEvent deleteAssignment(APIGatewayProxyRequestEvent request, Matcher m,
                                                            String teacherId) throws SQLException {
        String sectionId = m.group(1);
        requireOwnedSection(teacherId, sectionId);
        return wrap(GradebookService.deleteAssignment(sectionId, m.group(2)));
    }

    // ---- Grades ----

    private APIGatewayProxyResponseEvent upsertAssignmentGrade(APIGatewayProxyRequestEvent request, Matcher m,
                                                                 String teacherId) throws SQLException {
        String assignmentId = m.group(1);
        String studentId = m.group(2);
        requireOwnedAssignment(teacherId, assignmentId);
        UpsertAssignmentGradeRequest body = parseBody(request, UpsertAssignmentGradeRequest.class);
        String status = body.status() == null ? "graded" : body.status();
        if (!ASSIGNMENT_GRADE_STATUSES.contains(status)) {
            throw ApiException.badRequest("status must be one of " + ASSIGNMENT_GRADE_STATUSES);
        }
        return wrap(GradebookService.upsertAssignmentGrade(assignmentId, studentId, body.pointsEarned(), status,
                body.teacherComment()));
    }

    private APIGatewayProxyResponseEvent upsertTermGrade(APIGatewayProxyRequestEvent request, Matcher m,
                                                           String teacherId) throws SQLException {
        String sectionId = m.group(1);
        String studentId = m.group(2);
        requireOwnedSection(teacherId, sectionId);
        UpsertTermGradeRequest body = parseBody(request, UpsertTermGradeRequest.class);
        if (isBlank(body.termId())) {
            throw ApiException.badRequest("termId is required");
        }
        boolean posted = Boolean.TRUE.equals(body.posted());
        return wrap(GradebookService.upsertTermGrade(sectionId, body.termId(), studentId, body.finalPct(),
                body.finalLetter(), body.gpaPoints(), posted));
    }

    // ---- Shared helpers ----

    private static String resolveTeacherId(String userAccountId) throws SQLException {
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id FROM teacher WHERE user_account_id = ?::uuid")) {
            stmt.setString(1, userAccountId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw ApiException.notFound("No teacher profile linked to this account");
                }
                return rs.getString("id");
            }
        }
    }

    private static void requireOwnedSection(String teacherId, String sectionId) throws SQLException {
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT 1 FROM section WHERE id = ?::uuid AND teacher_id = ?::uuid")) {
            stmt.setString(1, sectionId);
            stmt.setString(2, teacherId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw ApiException.notFound("Section not found");
                }
            }
        }
    }

    private static void requireOwnedAssignment(String teacherId, String assignmentId) throws SQLException {
        String sql = """
                SELECT 1 FROM assignment a
                JOIN section sec ON sec.id = a.section_id
                WHERE a.id = ?::uuid AND sec.teacher_id = ?::uuid
                """;
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, assignmentId);
            stmt.setString(2, teacherId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw ApiException.notFound("Assignment not found");
                }
            }
        }
    }

    /** Adapts GradebookService's plain (statusCode, body) pair to this handler's ApiResponses shape. */
    private static APIGatewayProxyResponseEvent wrap(GradebookService.APIGatewayResponse response) {
        return ApiResponses.json(response.statusCode(), response.body());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static <T> T parseBody(APIGatewayProxyRequestEvent request, Class<T> type) {
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

    // ---- Dispatch plumbing ----

    private record Route(String method, Pattern pattern, RouteHandler handler) {
    }

    private interface RouteHandler {
        APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent request, Matcher matcher, String teacherId)
                throws SQLException;
    }

    // ---- Request DTOs (same shape as AdminHandler's, kept local since each Lambda parses its own input) ----

    private record CreateCategoryRequest(String name, BigDecimal weightPct) {
    }

    private record UpdateCategoryRequest(String name, BigDecimal weightPct) {
    }

    private record CreateAssignmentRequest(String categoryId, String name, String description,
                                            BigDecimal pointsPossible, String assignedDate, String dueDate) {
    }

    private record UpdateAssignmentRequest(String categoryId, String name, String description,
                                            BigDecimal pointsPossible, String assignedDate, String dueDate) {
    }

    private record UpsertAssignmentGradeRequest(BigDecimal pointsEarned, String status, String teacherComment) {
    }

    private record UpsertTermGradeRequest(String termId, BigDecimal finalPct, String finalLetter,
                                           BigDecimal gpaPoints, Boolean posted) {
    }
}
