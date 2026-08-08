-- GradeTrack dev fixture data — NOT a schema migration, do not run via flyway:migrate.
-- Run with: mvn -f db/pom.xml generate-resources sql:execute@seed \
--   -Ddb.url=... -Ddb.user=... -Ddb.password=... -DdevParentUserId=<uuid from a real /auth/signup>
--
-- Attaches one fake student with a couple of graded assignments and a posted term grade
-- to whichever real user_account you pass as devParentUserId, so the students/grades
-- endpoints have something real to return. Safe to re-run (every insert is idempotent).

INSERT INTO school (id, name, district_name, address)
VALUES ('00000000-0000-0000-0000-000000000001', 'Lincoln Elementary', 'Lincoln Unified School District', '100 Lincoln Way')
ON CONFLICT (id) DO NOTHING;

INSERT INTO academic_year (id, school_id, name, start_date, end_date)
VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
        '2025-2026', '2025-08-15', '2026-06-05')
ON CONFLICT (id) DO NOTHING;

INSERT INTO term (id, academic_year_id, name, term_type, start_date, end_date)
VALUES ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002',
        'Fall Semester', 'semester', '2025-08-15', '2025-12-19')
ON CONFLICT (id) DO NOTHING;

INSERT INTO course (id, school_id, name, subject, course_code)
VALUES ('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001',
        'Math 5', 'Math', 'MATH5')
ON CONFLICT (id) DO NOTHING;

INSERT INTO teacher (id, user_account_id, first_name, last_name, school_id, email)
VALUES ('00000000-0000-0000-0000-000000000005', NULL, 'Dana', 'Osei',
        '00000000-0000-0000-0000-000000000001', 'dana.osei@lincoln.example.edu')
ON CONFLICT (id) DO NOTHING;

INSERT INTO section (id, course_id, term_id, teacher_id, period, room)
VALUES ('00000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000004',
        '00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000005',
        'Period 3', 'Room 12')
ON CONFLICT (id) DO NOTHING;

INSERT INTO student (id, first_name, last_name, date_of_birth, current_school_id)
VALUES ('00000000-0000-0000-0000-000000000007', 'Alex', 'Rivera', '2014-03-22',
        '00000000-0000-0000-0000-000000000001')
ON CONFLICT (id) DO NOTHING;

INSERT INTO guardian_student (guardian_id, student_id, relationship, is_primary, access_level)
VALUES ('${devParentUserId}', '00000000-0000-0000-0000-000000000007', 'parent', true, 'full')
ON CONFLICT (guardian_id, student_id) DO NOTHING;

INSERT INTO enrollment (id, student_id, section_id, enrolled_on, status)
VALUES ('00000000-0000-0000-0000-000000000008', '00000000-0000-0000-0000-000000000007',
        '00000000-0000-0000-0000-000000000006', '2025-08-15', 'active')
ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment_category (id, section_id, name, weight_pct)
VALUES
    ('00000000-0000-0000-0000-000000000009', '00000000-0000-0000-0000-000000000006', 'Homework', 40.00),
    ('00000000-0000-0000-0000-00000000000a', '00000000-0000-0000-0000-000000000006', 'Tests', 60.00)
ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment (id, section_id, category_id, name, points_possible, assigned_date, due_date)
VALUES
    ('00000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-000000000006',
     '00000000-0000-0000-0000-000000000009', 'Fractions Worksheet 1', 20.00, '2025-09-02', '2025-09-05'),
    ('00000000-0000-0000-0000-00000000000c', '00000000-0000-0000-0000-000000000006',
     '00000000-0000-0000-0000-000000000009', 'Fractions Worksheet 2', 20.00, '2025-09-09', '2025-09-12'),
    ('00000000-0000-0000-0000-00000000000d', '00000000-0000-0000-0000-000000000006',
     '00000000-0000-0000-0000-00000000000a', 'Unit 1 Test', 100.00, '2025-09-15', '2025-09-19')
ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment_grade (assignment_id, student_id, points_earned, status, graded_at)
VALUES
    ('00000000-0000-0000-0000-00000000000b', '00000000-0000-0000-0000-000000000007', 18.00, 'graded', '2025-09-06 10:00:00'),
    ('00000000-0000-0000-0000-00000000000c', '00000000-0000-0000-0000-000000000007', 20.00, 'graded', '2025-09-13 10:00:00'),
    ('00000000-0000-0000-0000-00000000000d', '00000000-0000-0000-0000-000000000007', 87.00, 'graded', '2025-09-20 10:00:00')
ON CONFLICT (assignment_id, student_id) DO NOTHING;

INSERT INTO term_grade (id, student_id, section_id, term_id, final_pct, final_letter, gpa_points, posted_at)
VALUES ('00000000-0000-0000-0000-00000000000e', '00000000-0000-0000-0000-000000000007',
        '00000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000003',
        91.50, 'A-', 3.7, '2025-12-19 09:00:00')
ON CONFLICT (id) DO NOTHING;

-- One example rule the dev parent has already configured, plus two notifications
-- (one read, one not) so the Alerts UI has something real to show before any
-- detection engine exists to generate these for real.

INSERT INTO alert_rule (id, guardian_id, student_id, trigger_type, threshold_value, channel, active)
VALUES ('00000000-0000-0000-0000-00000000000f', '${devParentUserId}', '00000000-0000-0000-0000-000000000007',
        'grade_below', 70.00, 'in_app', true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO notification (id, user_account_id, alert_rule_id, student_id, message, related_entity_type,
                           related_entity_id, read_at, created_at)
VALUES ('00000000-0000-0000-0000-000000000010', '${devParentUserId}', '00000000-0000-0000-0000-00000000000f',
        '00000000-0000-0000-0000-000000000007', 'Alex''s Unit 1 Test score (87%) is in for Math 5.',
        'assignment_grade', '00000000-0000-0000-0000-00000000000d', NULL, '2025-09-20 10:05:00')
ON CONFLICT (id) DO NOTHING;

INSERT INTO notification (id, user_account_id, alert_rule_id, student_id, message, related_entity_type,
                           related_entity_id, read_at, created_at)
VALUES ('00000000-0000-0000-0000-000000000011', '${devParentUserId}', NULL,
        '00000000-0000-0000-0000-000000000007', 'Fall Semester report card is posted for Alex - Math 5: A-.',
        'term_grade', '00000000-0000-0000-0000-00000000000e', '2025-12-19 12:00:00', '2025-12-19 09:05:00')
ON CONFLICT (id) DO NOTHING;
