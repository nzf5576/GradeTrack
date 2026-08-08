package com.gradetrack.admin;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.gradetrack.common.ApiException;
import com.gradetrack.common.ApiResponses;
import com.gradetrack.common.AuthContext;
import com.gradetrack.common.Db;
import com.gradetrack.common.GradebookService;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Admin console backend: org setup (schools/years/terms/courses/teachers/sections),
 * student + guardian linking, enrollment, and the gradebook (categories, assignments,
 * scores, posted term grades). Every route requires role=admin. Given the route count,
 * this uses a small dispatch table plus insertReturningId/queryList helpers instead of
 * repeating the same JDBC boilerplate ~20 times, unlike the smaller handlers.
 */
public class AdminHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Set<String> TERM_TYPES = Set.of("semester", "quarter", "trimester", "full_year");
    private static final Set<String> GUARDIAN_RELATIONSHIPS = Set.of("parent", "guardian", "other");
    private static final Set<String> GUARDIAN_ACCESS_LEVELS = Set.of("full", "view_only");
    private static final Set<String> ASSIGNMENT_GRADE_STATUSES =
            Set.of("graded", "missing", "late", "excused", "pending");

    private static final Pattern SCHOOLS_PATH = Pattern.compile("^.*/admin/schools/?$");
    private static final Pattern SCHOOL_ITEM_PATH = Pattern.compile("^.*/admin/schools/([^/]+)/?$");
    private static final Pattern ACADEMIC_YEARS_PATH = Pattern.compile("^.*/admin/academic-years/?$");
    private static final Pattern ACADEMIC_YEAR_ITEM_PATH =
            Pattern.compile("^.*/admin/academic-years/([^/]+)/?$");
    private static final Pattern TERMS_PATH = Pattern.compile("^.*/admin/terms/?$");
    private static final Pattern TERM_ITEM_PATH = Pattern.compile("^.*/admin/terms/([^/]+)/?$");
    private static final Pattern COURSES_PATH = Pattern.compile("^.*/admin/courses/?$");
    private static final Pattern COURSE_ITEM_PATH = Pattern.compile("^.*/admin/courses/([^/]+)/?$");
    private static final Pattern TEACHERS_PATH = Pattern.compile("^.*/admin/teachers/?$");
    private static final Pattern TEACHER_ITEM_PATH = Pattern.compile("^.*/admin/teachers/([^/]+)/?$");
    private static final Pattern TEACHER_LINK_ACCOUNT_PATH =
            Pattern.compile("^.*/admin/teachers/([^/]+)/link-account/?$");
    private static final Pattern SECTIONS_PATH = Pattern.compile("^.*/admin/sections/?$");
    private static final Pattern SECTION_ITEM_PATH = Pattern.compile("^.*/admin/sections/([^/]+)/?$");
    private static final Pattern STUDENTS_PATH = Pattern.compile("^.*/admin/students/?$");
    private static final Pattern STUDENT_ITEM_PATH = Pattern.compile("^.*/admin/students/([^/]+)/?$");
    private static final Pattern STUDENT_GUARDIANS_PATH =
            Pattern.compile("^.*/admin/students/([^/]+)/guardians/?$");
    private static final Pattern STUDENT_GUARDIAN_ITEM_PATH =
            Pattern.compile("^.*/admin/students/([^/]+)/guardians/([^/]+)/?$");
    private static final Pattern ENROLLMENTS_PATH = Pattern.compile("^.*/admin/enrollments/?$");
    private static final Pattern ENROLLMENT_ITEM_PATH = Pattern.compile("^.*/admin/enrollments/([^/]+)/?$");
    private static final Pattern SECTION_CATEGORIES_PATH =
            Pattern.compile("^.*/admin/sections/([^/]+)/categories/?$");
    private static final Pattern SECTION_CATEGORY_ITEM_PATH =
            Pattern.compile("^.*/admin/sections/([^/]+)/categories/([^/]+)/?$");
    private static final Pattern SECTION_ASSIGNMENTS_PATH =
            Pattern.compile("^.*/admin/sections/([^/]+)/assignments/?$");
    private static final Pattern SECTION_ASSIGNMENT_ITEM_PATH =
            Pattern.compile("^.*/admin/sections/([^/]+)/assignments/([^/]+)/?$");
    private static final Pattern ASSIGNMENT_GRADE_PATH =
            Pattern.compile("^.*/admin/assignments/([^/]+)/grades/([^/]+)/?$");
    private static final Pattern SECTION_TERM_GRADE_PATH =
            Pattern.compile("^.*/admin/sections/([^/]+)/term-grades/([^/]+)/?$");

    private final List<Route> routes = List.of(
            new Route("POST", SCHOOLS_PATH, this::createSchool),
            new Route("GET", SCHOOLS_PATH, this::listSchools),
            new Route("PUT", SCHOOL_ITEM_PATH, this::updateSchool),
            new Route("DELETE", SCHOOL_ITEM_PATH, this::deleteSchool),
            new Route("POST", ACADEMIC_YEARS_PATH, this::createAcademicYear),
            new Route("GET", ACADEMIC_YEARS_PATH, this::listAcademicYears),
            new Route("PUT", ACADEMIC_YEAR_ITEM_PATH, this::updateAcademicYear),
            new Route("DELETE", ACADEMIC_YEAR_ITEM_PATH, this::deleteAcademicYear),
            new Route("POST", TERMS_PATH, this::createTerm),
            new Route("GET", TERMS_PATH, this::listTerms),
            new Route("PUT", TERM_ITEM_PATH, this::updateTerm),
            new Route("DELETE", TERM_ITEM_PATH, this::deleteTerm),
            new Route("POST", COURSES_PATH, this::createCourse),
            new Route("GET", COURSES_PATH, this::listCourses),
            new Route("PUT", COURSE_ITEM_PATH, this::updateCourse),
            new Route("DELETE", COURSE_ITEM_PATH, this::deleteCourse),
            new Route("POST", TEACHERS_PATH, this::createTeacher),
            new Route("GET", TEACHERS_PATH, this::listTeachers),
            new Route("PUT", TEACHER_ITEM_PATH, this::updateTeacher),
            new Route("DELETE", TEACHER_ITEM_PATH, this::deleteTeacher),
            new Route("POST", TEACHER_LINK_ACCOUNT_PATH, this::linkTeacherAccount),
            new Route("POST", SECTIONS_PATH, this::createSection),
            new Route("GET", SECTIONS_PATH, this::listSections),
            new Route("PUT", SECTION_ITEM_PATH, this::updateSection),
            new Route("DELETE", SECTION_ITEM_PATH, this::deleteSection),
            new Route("POST", STUDENTS_PATH, this::createStudent),
            new Route("GET", STUDENTS_PATH, this::listStudents),
            new Route("PUT", STUDENT_ITEM_PATH, this::updateStudent),
            new Route("DELETE", STUDENT_ITEM_PATH, this::deleteStudent),
            new Route("POST", STUDENT_GUARDIANS_PATH, this::linkGuardian),
            new Route("GET", STUDENT_GUARDIANS_PATH, this::listGuardians),
            new Route("DELETE", STUDENT_GUARDIAN_ITEM_PATH, this::revokeGuardian),
            new Route("POST", ENROLLMENTS_PATH, this::createEnrollment),
            new Route("GET", ENROLLMENTS_PATH, this::listEnrollments),
            new Route("DELETE", ENROLLMENT_ITEM_PATH, this::deleteEnrollment),
            new Route("POST", SECTION_CATEGORIES_PATH, this::createCategory),
            new Route("GET", SECTION_CATEGORIES_PATH, this::listCategories),
            new Route("PUT", SECTION_CATEGORY_ITEM_PATH, this::updateCategory),
            new Route("DELETE", SECTION_CATEGORY_ITEM_PATH, this::deleteCategory),
            new Route("POST", SECTION_ASSIGNMENTS_PATH, this::createAssignment),
            new Route("GET", SECTION_ASSIGNMENTS_PATH, this::listAssignments),
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
            AuthContext.requireAdmin(request);

            for (Route route : routes) {
                Matcher matcher = route.pattern().matcher(path);
                if (route.method().equalsIgnoreCase(method) && matcher.matches()) {
                    return route.handler().handle(request, matcher);
                }
            }
            return ApiResponses.notFoundResponse();
        } catch (ApiException e) {
            return ApiResponses.error(e);
        } catch (Exception e) {
            context.getLogger().log("Unhandled error in AdminHandler: " + e);
            return ApiResponses.internalError();
        }
    }

    // ---- Schools ----

    private APIGatewayProxyResponseEvent createSchool(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        CreateSchoolRequest body = parseBody(request, CreateSchoolRequest.class);
        if (isBlank(body.name())) {
            throw ApiException.badRequest("name is required");
        }
        return insertReturningId(
                "INSERT INTO school (name, district_name, address) VALUES (?, ?, ?) RETURNING id",
                stmt -> {
                    stmt.setString(1, body.name());
                    stmt.setString(2, body.districtName());
                    stmt.setString(3, body.address());
                });
    }

    private APIGatewayProxyResponseEvent listSchools(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return queryList(
                "SELECT id, name, district_name FROM school ORDER BY name",
                "schools", stmt -> { }, rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("name", rs.getString("name"));
                    row.put("districtName", rs.getString("district_name"));
                    return row;
                });
    }

    private APIGatewayProxyResponseEvent updateSchool(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        UpdateSchoolRequest body = parseBody(request, UpdateSchoolRequest.class);
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE school SET name = COALESCE(?, name), district_name = COALESCE(?, district_name), "
                             + "address = COALESCE(?, address) WHERE id = ?::uuid")) {
            stmt.setString(1, body.name());
            stmt.setString(2, body.districtName());
            stmt.setString(3, body.address());
            stmt.setString(4, m.group(1));
            executeUpdateOrNotFound(stmt, "School not found");
        }
        return ApiResponses.ok(Map.of("updated", true));
    }

    private APIGatewayProxyResponseEvent deleteSchool(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return deleteById("DELETE FROM school WHERE id = ?::uuid",
                stmt -> stmt.setString(1, m.group(1)), "School not found");
    }

    // ---- Academic years ----

    private APIGatewayProxyResponseEvent createAcademicYear(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        CreateAcademicYearRequest body = parseBody(request, CreateAcademicYearRequest.class);
        if (isBlank(body.schoolId()) || isBlank(body.name()) || isBlank(body.startDate())
                || isBlank(body.endDate())) {
            throw ApiException.badRequest("schoolId, name, startDate, and endDate are required");
        }
        return insertReturningId(
                "INSERT INTO academic_year (school_id, name, start_date, end_date) "
                        + "VALUES (?::uuid, ?, ?::date, ?::date) RETURNING id",
                stmt -> {
                    stmt.setString(1, body.schoolId());
                    stmt.setString(2, body.name());
                    stmt.setString(3, body.startDate());
                    stmt.setString(4, body.endDate());
                });
    }

    private APIGatewayProxyResponseEvent listAcademicYears(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return queryList(
                """
                SELECT ay.id, ay.name, ay.start_date, ay.end_date, ay.school_id, s.name AS school_name
                FROM academic_year ay
                JOIN school s ON s.id = ay.school_id
                ORDER BY ay.start_date DESC
                """,
                "academicYears", stmt -> { }, rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("name", rs.getString("name"));
                    row.put("startDate", rs.getString("start_date"));
                    row.put("endDate", rs.getString("end_date"));
                    row.put("schoolId", rs.getString("school_id"));
                    row.put("schoolName", rs.getString("school_name"));
                    return row;
                });
    }

    private APIGatewayProxyResponseEvent updateAcademicYear(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        UpdateAcademicYearRequest body = parseBody(request, UpdateAcademicYearRequest.class);
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE academic_year SET school_id = COALESCE(?::uuid, school_id), "
                             + "name = COALESCE(?, name), start_date = COALESCE(?::date, start_date), "
                             + "end_date = COALESCE(?::date, end_date) WHERE id = ?::uuid")) {
            bindNullable(stmt, 1, body.schoolId());
            stmt.setString(2, body.name());
            stmt.setString(3, body.startDate());
            stmt.setString(4, body.endDate());
            stmt.setString(5, m.group(1));
            executeUpdateOrNotFound(stmt, "Academic year not found");
        }
        return ApiResponses.ok(Map.of("updated", true));
    }

    private APIGatewayProxyResponseEvent deleteAcademicYear(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return deleteById("DELETE FROM academic_year WHERE id = ?::uuid",
                stmt -> stmt.setString(1, m.group(1)), "Academic year not found");
    }

    // ---- Terms ----

    private APIGatewayProxyResponseEvent createTerm(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        CreateTermRequest body = parseBody(request, CreateTermRequest.class);
        if (isBlank(body.academicYearId()) || isBlank(body.name()) || isBlank(body.termType())
                || isBlank(body.startDate()) || isBlank(body.endDate())) {
            throw ApiException.badRequest("academicYearId, name, termType, startDate, and endDate are required");
        }
        if (!TERM_TYPES.contains(body.termType())) {
            throw ApiException.badRequest("termType must be one of " + TERM_TYPES);
        }
        return insertReturningId(
                "INSERT INTO term (academic_year_id, name, term_type, start_date, end_date) "
                        + "VALUES (?::uuid, ?, ?, ?::date, ?::date) RETURNING id",
                stmt -> {
                    stmt.setString(1, body.academicYearId());
                    stmt.setString(2, body.name());
                    stmt.setString(3, body.termType());
                    stmt.setString(4, body.startDate());
                    stmt.setString(5, body.endDate());
                });
    }

    private APIGatewayProxyResponseEvent listTerms(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return queryList(
                """
                SELECT t.id, t.name, t.term_type, t.start_date, t.end_date, t.academic_year_id,
                       ay.name AS academic_year_name
                FROM term t
                JOIN academic_year ay ON ay.id = t.academic_year_id
                ORDER BY t.start_date DESC
                """,
                "terms", stmt -> { }, rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("name", rs.getString("name"));
                    row.put("termType", rs.getString("term_type"));
                    row.put("startDate", rs.getString("start_date"));
                    row.put("endDate", rs.getString("end_date"));
                    row.put("academicYearId", rs.getString("academic_year_id"));
                    row.put("academicYearName", rs.getString("academic_year_name"));
                    return row;
                });
    }

    private APIGatewayProxyResponseEvent updateTerm(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        UpdateTermRequest body = parseBody(request, UpdateTermRequest.class);
        if (body.termType() != null && !TERM_TYPES.contains(body.termType())) {
            throw ApiException.badRequest("termType must be one of " + TERM_TYPES);
        }
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE term SET name = COALESCE(?, name), term_type = COALESCE(?, term_type), "
                             + "start_date = COALESCE(?::date, start_date), end_date = COALESCE(?::date, end_date) "
                             + "WHERE id = ?::uuid")) {
            stmt.setString(1, body.name());
            stmt.setString(2, body.termType());
            stmt.setString(3, body.startDate());
            stmt.setString(4, body.endDate());
            stmt.setString(5, m.group(1));
            executeUpdateOrNotFound(stmt, "Term not found");
        }
        return ApiResponses.ok(Map.of("updated", true));
    }

    private APIGatewayProxyResponseEvent deleteTerm(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return deleteById("DELETE FROM term WHERE id = ?::uuid",
                stmt -> stmt.setString(1, m.group(1)), "Term not found");
    }

    // ---- Courses ----

    private APIGatewayProxyResponseEvent createCourse(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        CreateCourseRequest body = parseBody(request, CreateCourseRequest.class);
        if (isBlank(body.schoolId()) || isBlank(body.name())) {
            throw ApiException.badRequest("schoolId and name are required");
        }
        return insertReturningId(
                "INSERT INTO course (school_id, name, subject, course_code) VALUES (?::uuid, ?, ?, ?) RETURNING id",
                stmt -> {
                    stmt.setString(1, body.schoolId());
                    stmt.setString(2, body.name());
                    stmt.setString(3, body.subject());
                    stmt.setString(4, body.courseCode());
                });
    }

    private APIGatewayProxyResponseEvent listCourses(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return queryList(
                "SELECT id, name, subject, course_code, school_id FROM course ORDER BY name",
                "courses", stmt -> { }, rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("name", rs.getString("name"));
                    row.put("subject", rs.getString("subject"));
                    row.put("courseCode", rs.getString("course_code"));
                    row.put("schoolId", rs.getString("school_id"));
                    return row;
                });
    }

    private APIGatewayProxyResponseEvent updateCourse(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        UpdateCourseRequest body = parseBody(request, UpdateCourseRequest.class);
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE course SET name = COALESCE(?, name), subject = COALESCE(?, subject), "
                             + "course_code = COALESCE(?, course_code) WHERE id = ?::uuid")) {
            stmt.setString(1, body.name());
            stmt.setString(2, body.subject());
            stmt.setString(3, body.courseCode());
            stmt.setString(4, m.group(1));
            executeUpdateOrNotFound(stmt, "Course not found");
        }
        return ApiResponses.ok(Map.of("updated", true));
    }

    private APIGatewayProxyResponseEvent deleteCourse(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return deleteById("DELETE FROM course WHERE id = ?::uuid",
                stmt -> stmt.setString(1, m.group(1)), "Course not found");
    }

    // ---- Teachers ----

    private APIGatewayProxyResponseEvent createTeacher(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        CreateTeacherRequest body = parseBody(request, CreateTeacherRequest.class);
        if (isBlank(body.schoolId()) || isBlank(body.firstName()) || isBlank(body.lastName())) {
            throw ApiException.badRequest("schoolId, firstName, and lastName are required");
        }
        return insertReturningId(
                "INSERT INTO teacher (school_id, first_name, last_name, email) "
                        + "VALUES (?::uuid, ?, ?, ?) RETURNING id",
                stmt -> {
                    stmt.setString(1, body.schoolId());
                    stmt.setString(2, body.firstName());
                    stmt.setString(3, body.lastName());
                    stmt.setString(4, body.email());
                });
    }

    private APIGatewayProxyResponseEvent listTeachers(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return queryList(
                "SELECT id, first_name, last_name, email, school_id FROM teacher ORDER BY last_name",
                "teachers", stmt -> { }, rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("firstName", rs.getString("first_name"));
                    row.put("lastName", rs.getString("last_name"));
                    row.put("email", rs.getString("email"));
                    row.put("schoolId", rs.getString("school_id"));
                    return row;
                });
    }

    private APIGatewayProxyResponseEvent updateTeacher(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        UpdateTeacherRequest body = parseBody(request, UpdateTeacherRequest.class);
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE teacher SET first_name = COALESCE(?, first_name), "
                             + "last_name = COALESCE(?, last_name), email = COALESCE(?, email) WHERE id = ?::uuid")) {
            stmt.setString(1, body.firstName());
            stmt.setString(2, body.lastName());
            stmt.setString(3, body.email());
            stmt.setString(4, m.group(1));
            executeUpdateOrNotFound(stmt, "Teacher not found");
        }
        return ApiResponses.ok(Map.of("updated", true));
    }

    private APIGatewayProxyResponseEvent deleteTeacher(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return deleteById("DELETE FROM teacher WHERE id = ?::uuid",
                stmt -> stmt.setString(1, m.group(1)), "Teacher not found");
    }

    /** Sets role='teacher' on the account and links it as this teacher's login, mirroring linkGuardian. */
    private APIGatewayProxyResponseEvent linkTeacherAccount(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        String teacherId = m.group(1);
        LinkTeacherAccountRequest body = parseBody(request, LinkTeacherAccountRequest.class);
        if (isBlank(body.email())) {
            throw ApiException.badRequest("email is required");
        }

        String userAccountId;
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT id FROM user_account WHERE email = ?")) {
            stmt.setString(1, body.email().toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw ApiException.badRequest("No account found for that email — they must sign up first");
                }
                userAccountId = rs.getString("id");
            }
        }

        try (Connection conn = Db.dataSource().getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE user_account SET role = 'teacher' WHERE id = ?::uuid")) {
                stmt.setString(1, userAccountId);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE teacher SET user_account_id = ?::uuid WHERE id = ?::uuid")) {
                stmt.setString(1, userAccountId);
                stmt.setString(2, teacherId);
                if (stmt.executeUpdate() == 0) {
                    throw ApiException.notFound("Teacher not found");
                }
            }
        }
        return ApiResponses.ok(Map.of("linked", true));
    }

    // ---- Sections ----

    private APIGatewayProxyResponseEvent createSection(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        CreateSectionRequest body = parseBody(request, CreateSectionRequest.class);
        if (isBlank(body.courseId()) || isBlank(body.termId()) || isBlank(body.teacherId())) {
            throw ApiException.badRequest("courseId, termId, and teacherId are required");
        }
        return insertReturningId(
                "INSERT INTO section (course_id, term_id, teacher_id, period, room) "
                        + "VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?) RETURNING id",
                stmt -> {
                    stmt.setString(1, body.courseId());
                    stmt.setString(2, body.termId());
                    stmt.setString(3, body.teacherId());
                    stmt.setString(4, body.period());
                    stmt.setString(5, body.room());
                });
    }

    private APIGatewayProxyResponseEvent listSections(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return queryList(
                """
                SELECT sec.id, c.name AS course_name, t.name AS term_name,
                       tr.first_name AS teacher_first_name, tr.last_name AS teacher_last_name,
                       sec.period, sec.room
                FROM section sec
                JOIN course c ON c.id = sec.course_id
                JOIN term t ON t.id = sec.term_id
                JOIN teacher tr ON tr.id = sec.teacher_id
                ORDER BY c.name
                """,
                "sections", stmt -> { }, rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("courseName", rs.getString("course_name"));
                    row.put("termName", rs.getString("term_name"));
                    row.put("teacherName", rs.getString("teacher_first_name") + " " + rs.getString("teacher_last_name"));
                    row.put("period", rs.getString("period"));
                    row.put("room", rs.getString("room"));
                    return row;
                });
    }

    private APIGatewayProxyResponseEvent updateSection(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        UpdateSectionRequest body = parseBody(request, UpdateSectionRequest.class);
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE section SET teacher_id = COALESCE(?::uuid, teacher_id), "
                             + "period = COALESCE(?, period), room = COALESCE(?, room) WHERE id = ?::uuid")) {
            bindNullable(stmt, 1, body.teacherId());
            stmt.setString(2, body.period());
            stmt.setString(3, body.room());
            stmt.setString(4, m.group(1));
            executeUpdateOrNotFound(stmt, "Section not found");
        }
        return ApiResponses.ok(Map.of("updated", true));
    }

    private APIGatewayProxyResponseEvent deleteSection(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return deleteById("DELETE FROM section WHERE id = ?::uuid",
                stmt -> stmt.setString(1, m.group(1)), "Section not found");
    }

    // ---- Students ----

    private APIGatewayProxyResponseEvent createStudent(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        CreateStudentRequest body = parseBody(request, CreateStudentRequest.class);
        if (isBlank(body.firstName()) || isBlank(body.lastName())) {
            throw ApiException.badRequest("firstName and lastName are required");
        }
        return insertReturningId(
                "INSERT INTO student (first_name, last_name, date_of_birth, current_school_id) "
                        + "VALUES (?, ?, ?::date, ?::uuid) RETURNING id",
                stmt -> {
                    stmt.setString(1, body.firstName());
                    stmt.setString(2, body.lastName());
                    stmt.setString(3, body.dateOfBirth());
                    bindNullable(stmt, 4, body.currentSchoolId());
                });
    }

    private APIGatewayProxyResponseEvent listStudents(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return queryList(
                """
                SELECT s.id, s.first_name, s.last_name, s.date_of_birth, sch.name AS school_name
                FROM student s
                LEFT JOIN school sch ON sch.id = s.current_school_id
                ORDER BY s.last_name
                """,
                "students", stmt -> { }, rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("firstName", rs.getString("first_name"));
                    row.put("lastName", rs.getString("last_name"));
                    row.put("dateOfBirth", rs.getString("date_of_birth"));
                    row.put("schoolName", rs.getString("school_name"));
                    return row;
                });
    }

    private APIGatewayProxyResponseEvent updateStudent(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        UpdateStudentRequest body = parseBody(request, UpdateStudentRequest.class);
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE student SET first_name = COALESCE(?, first_name), "
                             + "last_name = COALESCE(?, last_name), date_of_birth = COALESCE(?::date, date_of_birth), "
                             + "current_school_id = COALESCE(?::uuid, current_school_id) WHERE id = ?::uuid")) {
            stmt.setString(1, body.firstName());
            stmt.setString(2, body.lastName());
            stmt.setString(3, body.dateOfBirth());
            bindNullable(stmt, 4, body.currentSchoolId());
            stmt.setString(5, m.group(1));
            executeUpdateOrNotFound(stmt, "Student not found");
        }
        return ApiResponses.ok(Map.of("updated", true));
    }

    private APIGatewayProxyResponseEvent deleteStudent(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return deleteById("DELETE FROM student WHERE id = ?::uuid",
                stmt -> stmt.setString(1, m.group(1)), "Student not found");
    }

    // ---- Guardians ----

    private APIGatewayProxyResponseEvent linkGuardian(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        String studentId = m.group(1);
        LinkGuardianRequest body = parseBody(request, LinkGuardianRequest.class);
        if (isBlank(body.guardianEmail()) || isBlank(body.relationship())) {
            throw ApiException.badRequest("guardianEmail and relationship are required");
        }
        if (!GUARDIAN_RELATIONSHIPS.contains(body.relationship())) {
            throw ApiException.badRequest("relationship must be one of " + GUARDIAN_RELATIONSHIPS);
        }
        String accessLevel = body.accessLevel() == null ? "full" : body.accessLevel();
        if (!GUARDIAN_ACCESS_LEVELS.contains(accessLevel)) {
            throw ApiException.badRequest("accessLevel must be one of " + GUARDIAN_ACCESS_LEVELS);
        }

        String guardianId;
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT id FROM user_account WHERE email = ?")) {
            stmt.setString(1, body.guardianEmail().toLowerCase());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw ApiException.badRequest(
                            "No account found for that email — they must sign up first");
                }
                guardianId = rs.getString("id");
            }
        }

        return insertReturningId(
                """
                INSERT INTO guardian_student (guardian_id, student_id, relationship, is_primary, access_level)
                VALUES (?::uuid, ?::uuid, ?, ?, ?)
                ON CONFLICT (guardian_id, student_id) DO UPDATE SET
                    relationship = EXCLUDED.relationship,
                    is_primary = EXCLUDED.is_primary,
                    access_level = EXCLUDED.access_level
                RETURNING id
                """,
                stmt -> {
                    stmt.setString(1, guardianId);
                    stmt.setString(2, studentId);
                    stmt.setString(3, body.relationship());
                    stmt.setBoolean(4, Boolean.TRUE.equals(body.isPrimary()));
                    stmt.setString(5, accessLevel);
                });
    }

    private APIGatewayProxyResponseEvent listGuardians(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        String studentId = m.group(1);
        return queryList(
                """
                SELECT gs.guardian_id, gs.relationship, gs.is_primary, gs.access_level,
                       u.email AS guardian_email
                FROM guardian_student gs
                JOIN user_account u ON u.id = gs.guardian_id
                WHERE gs.student_id = ?::uuid
                ORDER BY u.email
                """,
                "guardians", stmt -> stmt.setString(1, studentId), rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("guardianId", rs.getString("guardian_id"));
                    row.put("guardianEmail", rs.getString("guardian_email"));
                    row.put("relationship", rs.getString("relationship"));
                    row.put("isPrimary", rs.getBoolean("is_primary"));
                    row.put("accessLevel", rs.getString("access_level"));
                    return row;
                });
    }

    private APIGatewayProxyResponseEvent revokeGuardian(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        String studentId = m.group(1);
        String guardianId = m.group(2);
        return deleteById(
                "DELETE FROM guardian_student WHERE student_id = ?::uuid AND guardian_id = ?::uuid",
                stmt -> {
                    stmt.setString(1, studentId);
                    stmt.setString(2, guardianId);
                }, "Guardian link not found");
    }

    // ---- Enrollments ----

    private APIGatewayProxyResponseEvent createEnrollment(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        CreateEnrollmentRequest body = parseBody(request, CreateEnrollmentRequest.class);
        if (isBlank(body.studentId()) || isBlank(body.sectionId())) {
            throw ApiException.badRequest("studentId and sectionId are required");
        }
        return insertReturningId(
                "INSERT INTO enrollment (student_id, section_id, enrolled_on, status) "
                        + "VALUES (?::uuid, ?::uuid, COALESCE(?::date, CURRENT_DATE), 'active') RETURNING id",
                stmt -> {
                    stmt.setString(1, body.studentId());
                    stmt.setString(2, body.sectionId());
                    stmt.setString(3, body.enrolledOn());
                });
    }

    private APIGatewayProxyResponseEvent listEnrollments(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return queryList(
                """
                SELECT e.id, e.status, e.student_id, e.section_id,
                       s.first_name AS student_first_name, s.last_name AS student_last_name,
                       c.name AS course_name
                FROM enrollment e
                JOIN student s ON s.id = e.student_id
                JOIN section sec ON sec.id = e.section_id
                JOIN course c ON c.id = sec.course_id
                ORDER BY s.last_name
                """,
                "enrollments", stmt -> { }, rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("studentId", rs.getString("student_id"));
                    row.put("sectionId", rs.getString("section_id"));
                    row.put("studentName", rs.getString("student_first_name") + " " + rs.getString("student_last_name"));
                    row.put("courseName", rs.getString("course_name"));
                    row.put("status", rs.getString("status"));
                    return row;
                });
    }

    private APIGatewayProxyResponseEvent deleteEnrollment(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return deleteById("DELETE FROM enrollment WHERE id = ?::uuid",
                stmt -> stmt.setString(1, m.group(1)), "Enrollment not found");
    }

    // ---- Assignment categories ----

    private APIGatewayProxyResponseEvent createCategory(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        String sectionId = m.group(1);
        CreateCategoryRequest body = parseBody(request, CreateCategoryRequest.class);
        if (isBlank(body.name()) || body.weightPct() == null) {
            throw ApiException.badRequest("name and weightPct are required");
        }
        return wrap(GradebookService.createCategory(sectionId, body.name(), body.weightPct()));
    }

    private APIGatewayProxyResponseEvent listCategories(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return wrap(GradebookService.listCategories(m.group(1)));
    }

    private APIGatewayProxyResponseEvent updateCategory(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        UpdateCategoryRequest body = parseBody(request, UpdateCategoryRequest.class);
        return wrap(GradebookService.updateCategory(m.group(1), m.group(2), body.name(), body.weightPct()));
    }

    private APIGatewayProxyResponseEvent deleteCategory(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return wrap(GradebookService.deleteCategory(m.group(1), m.group(2)));
    }

    // ---- Assignments ----

    private APIGatewayProxyResponseEvent createAssignment(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        String sectionId = m.group(1);
        CreateAssignmentRequest body = parseBody(request, CreateAssignmentRequest.class);
        if (isBlank(body.categoryId()) || isBlank(body.name()) || body.pointsPossible() == null) {
            throw ApiException.badRequest("categoryId, name, and pointsPossible are required");
        }
        return wrap(GradebookService.createAssignment(sectionId, body.categoryId(), body.name(),
                body.description(), body.pointsPossible(), body.assignedDate(), body.dueDate()));
    }

    private APIGatewayProxyResponseEvent listAssignments(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return wrap(GradebookService.listAssignments(m.group(1)));
    }

    private APIGatewayProxyResponseEvent updateAssignment(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        UpdateAssignmentRequest body = parseBody(request, UpdateAssignmentRequest.class);
        return wrap(GradebookService.updateAssignment(m.group(1), m.group(2), body.categoryId(), body.name(),
                body.description(), body.pointsPossible(), body.assignedDate(), body.dueDate()));
    }

    private APIGatewayProxyResponseEvent deleteAssignment(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        return wrap(GradebookService.deleteAssignment(m.group(1), m.group(2)));
    }

    // ---- Assignment grades ----

    private APIGatewayProxyResponseEvent upsertAssignmentGrade(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        String assignmentId = m.group(1);
        String studentId = m.group(2);
        UpsertAssignmentGradeRequest body = parseBody(request, UpsertAssignmentGradeRequest.class);
        String status = body.status() == null ? "graded" : body.status();
        if (!ASSIGNMENT_GRADE_STATUSES.contains(status)) {
            throw ApiException.badRequest("status must be one of " + ASSIGNMENT_GRADE_STATUSES);
        }
        return wrap(GradebookService.upsertAssignmentGrade(assignmentId, studentId, body.pointsEarned(), status,
                body.teacherComment()));
    }

    // ---- Term grades ----

    private APIGatewayProxyResponseEvent upsertTermGrade(APIGatewayProxyRequestEvent request, Matcher m)
            throws SQLException {
        String sectionId = m.group(1);
        String studentId = m.group(2);
        UpsertTermGradeRequest body = parseBody(request, UpsertTermGradeRequest.class);
        if (isBlank(body.termId())) {
            throw ApiException.badRequest("termId is required");
        }
        boolean posted = Boolean.TRUE.equals(body.posted());
        return wrap(GradebookService.upsertTermGrade(sectionId, body.termId(), studentId, body.finalPct(),
                body.finalLetter(), body.gpaPoints(), posted));
    }

    /** Adapts GradebookService's plain (statusCode, body) pair to this handler's ApiResponses shape. */
    private APIGatewayProxyResponseEvent wrap(GradebookService.APIGatewayResponse response) {
        return ApiResponses.json(response.statusCode(), response.body());
    }

    // ---- Shared helpers ----

    private APIGatewayProxyResponseEvent insertReturningId(String sql, ParamBinder binder) throws SQLException {
        try (Connection conn = Db.dataSource().getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            binder.bind(stmt);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return ApiResponses.created(Map.of("id", rs.getString("id")));
            }
        }
    }

    private APIGatewayProxyResponseEvent queryList(String sql, String rootKey, ParamBinder binder, RowMapper mapper)
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
        return ApiResponses.ok(Map.of(rootKey, rows));
    }

    /** 404s if the update touched zero rows (id doesn't exist). */
    private static void executeUpdateOrNotFound(PreparedStatement stmt, String notFoundMessage) throws SQLException {
        if (stmt.executeUpdate() == 0) {
            throw ApiException.notFound(notFoundMessage);
        }
    }

    /**
     * Runs a DELETE. 404 if it touched zero rows; 409 (not a raw 500) if Postgres rejects it
     * with a foreign-key violation because other records still reference this row — no
     * cascade logic, no pre-check queries, just translate the DB's own answer.
     */
    private static APIGatewayProxyResponseEvent deleteById(String sql, ParamBinder binder, String notFoundMessage)
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
        return ApiResponses.ok(Map.of("deleted", true));
    }

    private static void bindNullable(PreparedStatement stmt, int index, String value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, Types.OTHER);
        } else {
            stmt.setString(index, value);
        }
    }

    private static void bindNullable(PreparedStatement stmt, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            stmt.setNull(index, Types.NUMERIC);
        } else {
            stmt.setBigDecimal(index, value);
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

    // ---- Dispatch plumbing ----

    private record Route(String method, Pattern pattern, RouteHandler handler) {
    }

    private interface RouteHandler {
        APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent request, Matcher matcher)
                throws SQLException;
    }

    private interface ParamBinder {
        void bind(PreparedStatement stmt) throws SQLException;
    }

    private interface RowMapper {
        Map<String, Object> map(ResultSet rs) throws SQLException;
    }

    // ---- Request DTOs ----

    private record CreateSchoolRequest(String name, String districtName, String address) {
    }

    /** Every field optional — omitted fields are left unchanged (see updateSchool's COALESCE). */
    private record UpdateSchoolRequest(String name, String districtName, String address) {
    }

    private record CreateAcademicYearRequest(String schoolId, String name, String startDate, String endDate) {
    }

    private record UpdateAcademicYearRequest(String schoolId, String name, String startDate, String endDate) {
    }

    private record CreateTermRequest(String academicYearId, String name, String termType, String startDate,
                                      String endDate) {
    }

    private record UpdateTermRequest(String name, String termType, String startDate, String endDate) {
    }

    private record CreateCourseRequest(String schoolId, String name, String subject, String courseCode) {
    }

    private record UpdateCourseRequest(String name, String subject, String courseCode) {
    }

    private record CreateTeacherRequest(String schoolId, String firstName, String lastName, String email) {
    }

    private record UpdateTeacherRequest(String firstName, String lastName, String email) {
    }

    private record LinkTeacherAccountRequest(String email) {
    }

    private record CreateSectionRequest(String courseId, String termId, String teacherId, String period,
                                         String room) {
    }

    private record UpdateSectionRequest(String teacherId, String period, String room) {
    }

    private record CreateStudentRequest(String firstName, String lastName, String dateOfBirth,
                                         String currentSchoolId) {
    }

    private record UpdateStudentRequest(String firstName, String lastName, String dateOfBirth,
                                         String currentSchoolId) {
    }

    private record LinkGuardianRequest(String guardianEmail, String relationship, Boolean isPrimary,
                                        String accessLevel) {
    }

    private record CreateEnrollmentRequest(String studentId, String sectionId, String enrolledOn) {
    }

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
