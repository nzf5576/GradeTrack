package com.gradetrack.common;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Runs immediately after a grade write commits, in the same connection and Lambda
 * invocation as the write it follows (called from GradebookService's
 * upsertAssignmentGrade / upsertTermGrade, shared by both the admin and teacher
 * gradebook paths). Checks active alert_rule rows and writes notification rows when
 * a rule's condition is met. No scheduling, no queue, no real email/SMS send — this
 * only ever creates the same notification rows the Alerts page already reads.
 */
public final class AlertDetector {

    private AlertDetector() {
    }

    public static void checkMissingAssignment(Connection conn, String assignmentId, String assignmentGradeId,
                                                String studentId) throws SQLException {
        AssignmentContext ctx = fetchAssignmentContext(conn, assignmentId, studentId);
        if (ctx == null) {
            return;
        }
        String message = ctx.studentFirstName() + " is missing \"" + ctx.assignmentName()
                + "\" in " + ctx.courseName() + ".";

        String sql = matchingRuleSql("missing_assignment", false);
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, studentId);
            stmt.setString(2, studentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    notify(conn, rs.getString("id"), rs.getString("guardian_id"), studentId, message,
                            "assignment_grade", assignmentGradeId);
                }
            }
        }
    }

    public static void checkTermGrade(Connection conn, String sectionId, String termId, String termGradeId,
                                       String studentId, BigDecimal finalPct, String finalLetter, boolean posted)
            throws SQLException {
        if (finalPct != null) {
            checkGradeBelow(conn, sectionId, termId, termGradeId, studentId, finalPct);
            checkGradeDropPct(conn, sectionId, termId, termGradeId, studentId, finalPct);
        }
        if (posted) {
            checkReportCardPosted(conn, sectionId, termId, termGradeId, studentId, finalPct, finalLetter);
        }
    }

    private static void checkGradeBelow(Connection conn, String sectionId, String termId, String termGradeId,
                                         String studentId, BigDecimal finalPct) throws SQLException {
        TermGradeContext ctx = fetchTermGradeContext(conn, sectionId, termId, studentId);
        if (ctx == null) {
            return;
        }
        String message = ctx.studentFirstName() + "'s grade in " + ctx.courseName() + " (" + ctx.termName()
                + ") is " + finalPct + "%, below your threshold.";

        String sql = matchingRuleSql("grade_below", true);
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, studentId);
            stmt.setString(2, studentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    BigDecimal threshold = rs.getBigDecimal("threshold_value");
                    if (threshold != null && finalPct.compareTo(threshold) < 0) {
                        notify(conn, rs.getString("id"), rs.getString("guardian_id"), studentId, message,
                                "term_grade", termGradeId);
                    }
                }
            }
        }
    }

    private static void checkGradeDropPct(Connection conn, String sectionId, String termId, String termGradeId,
                                           String studentId, BigDecimal finalPct) throws SQLException {
        BigDecimal previousPct = fetchPreviousTermPct(conn, sectionId, termId, studentId);
        if (previousPct == null) {
            return;
        }
        BigDecimal drop = previousPct.subtract(finalPct);
        if (drop.signum() <= 0) {
            return;
        }
        TermGradeContext ctx = fetchTermGradeContext(conn, sectionId, termId, studentId);
        if (ctx == null) {
            return;
        }
        String message = ctx.studentFirstName() + "'s grade in " + ctx.courseName() + " dropped " + drop
                + "% (from " + previousPct + "% to " + finalPct + "%).";

        String sql = matchingRuleSql("grade_drop_pct", true);
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, studentId);
            stmt.setString(2, studentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    BigDecimal threshold = rs.getBigDecimal("threshold_value");
                    if (threshold != null && drop.compareTo(threshold) >= 0) {
                        notify(conn, rs.getString("id"), rs.getString("guardian_id"), studentId, message,
                                "term_grade", termGradeId);
                    }
                }
            }
        }
    }

    private static void checkReportCardPosted(Connection conn, String sectionId, String termId, String termGradeId,
                                               String studentId, BigDecimal finalPct, String finalLetter)
            throws SQLException {
        TermGradeContext ctx = fetchTermGradeContext(conn, sectionId, termId, studentId);
        if (ctx == null) {
            return;
        }
        String grade = finalLetter != null ? finalLetter : (finalPct != null ? finalPct + "%" : "posted");
        String message = ctx.studentFirstName() + "'s report card for " + ctx.termName() + " (" + ctx.courseName()
                + ") has been posted: " + grade + ".";

        String sql = matchingRuleSql("report_card_posted", false);
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, studentId);
            stmt.setString(2, studentId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    notify(conn, rs.getString("id"), rs.getString("guardian_id"), studentId, message,
                            "term_grade", termGradeId);
                }
            }
        }
    }

    /**
     * A guardian's rule matches if they're actually linked to the student (via
     * guardian_student) and the rule either applies to all their kids (student_id IS
     * NULL) or names this student specifically. includeThreshold just selects the
     * threshold_value column too, since grade_below/grade_drop_pct need it and the
     * others don't.
     */
    private static String matchingRuleSql(String triggerType, boolean includeThreshold) {
        return "SELECT ar.id, ar.guardian_id" + (includeThreshold ? ", ar.threshold_value" : "") + "\n"
                + "FROM alert_rule ar\n"
                + "JOIN guardian_student gs ON gs.guardian_id = ar.guardian_id AND gs.student_id = ?::uuid\n"
                + "WHERE ar.active = true AND ar.trigger_type = '" + triggerType + "'\n"
                + "  AND (ar.student_id IS NULL OR ar.student_id = ?::uuid)";
    }

    /** Dedup-guarded insert: skip if a notification for this rule + entity already exists. */
    private static void notify(Connection conn, String ruleId, String guardianId, String studentId, String message,
                                String relatedEntityType, String relatedEntityId) throws SQLException {
        String sql = """
                INSERT INTO notification (user_account_id, alert_rule_id, student_id, message,
                                           related_entity_type, related_entity_id)
                SELECT ?::uuid, ?::uuid, ?::uuid, ?, ?, ?::uuid
                WHERE NOT EXISTS (
                    SELECT 1 FROM notification WHERE alert_rule_id = ?::uuid AND related_entity_id = ?::uuid
                )
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, guardianId);
            stmt.setString(2, ruleId);
            stmt.setString(3, studentId);
            stmt.setString(4, message);
            stmt.setString(5, relatedEntityType);
            stmt.setString(6, relatedEntityId);
            stmt.setString(7, ruleId);
            stmt.setString(8, relatedEntityId);
            stmt.executeUpdate();
        }
    }

    private static AssignmentContext fetchAssignmentContext(Connection conn, String assignmentId, String studentId)
            throws SQLException {
        String sql = """
                SELECT a.name AS assignment_name, c.name AS course_name, s.first_name
                FROM assignment a
                JOIN section sec ON sec.id = a.section_id
                JOIN course c ON c.id = sec.course_id
                JOIN student s ON s.id = ?::uuid
                WHERE a.id = ?::uuid
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, studentId);
            stmt.setString(2, assignmentId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new AssignmentContext(rs.getString("assignment_name"), rs.getString("course_name"),
                        rs.getString("first_name"));
            }
        }
    }

    private static TermGradeContext fetchTermGradeContext(Connection conn, String sectionId, String termId,
                                                            String studentId) throws SQLException {
        String sql = """
                SELECT c.name AS course_name, t.name AS term_name, s.first_name
                FROM section sec
                JOIN course c ON c.id = sec.course_id
                CROSS JOIN term t
                CROSS JOIN student s
                WHERE sec.id = ?::uuid AND t.id = ?::uuid AND s.id = ?::uuid
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, sectionId);
            stmt.setString(2, termId);
            stmt.setString(3, studentId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new TermGradeContext(rs.getString("course_name"), rs.getString("term_name"),
                        rs.getString("first_name"));
            }
        }
    }

    private static BigDecimal fetchPreviousTermPct(Connection conn, String sectionId, String termId,
                                                     String studentId) throws SQLException {
        String sql = """
                SELECT tg.final_pct
                FROM term_grade tg
                JOIN section sec ON sec.id = tg.section_id
                JOIN term t ON t.id = tg.term_id
                WHERE tg.student_id = ?::uuid
                  AND sec.course_id = (SELECT course_id FROM section WHERE id = ?::uuid)
                  AND t.start_date < (SELECT start_date FROM term WHERE id = ?::uuid)
                  AND tg.final_pct IS NOT NULL
                ORDER BY t.start_date DESC
                LIMIT 1
                """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, studentId);
            stmt.setString(2, sectionId);
            stmt.setString(3, termId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getBigDecimal("final_pct") : null;
            }
        }
    }

    private record AssignmentContext(String assignmentName, String courseName, String studentFirstName) {
    }

    private record TermGradeContext(String courseName, String termName, String studentFirstName) {
    }
}
