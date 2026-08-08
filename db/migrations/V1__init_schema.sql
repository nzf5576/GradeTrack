-- GradeTrack v1 schema
-- Source: grade_tracker_data_model.md (Design notes / assumptions section applies)

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE user_account (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('parent','teacher','admin')),
    phone VARCHAR(20),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE school (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    district_name VARCHAR(255),
    address VARCHAR(255),
    external_sis_id VARCHAR(100)
);

CREATE TABLE student (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    date_of_birth DATE,
    external_sis_id VARCHAR(100),
    current_school_id UUID REFERENCES school(id),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE guardian_student (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    guardian_id UUID NOT NULL REFERENCES user_account(id),
    student_id UUID NOT NULL REFERENCES student(id),
    relationship VARCHAR(20) NOT NULL CHECK (relationship IN ('parent','guardian','other')),
    is_primary BOOLEAN NOT NULL DEFAULT false,
    access_level VARCHAR(20) NOT NULL DEFAULT 'full' CHECK (access_level IN ('full','view_only')),
    UNIQUE (guardian_id, student_id)
);

CREATE TABLE academic_year (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id UUID NOT NULL REFERENCES school(id),
    name VARCHAR(50) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL
);

CREATE TABLE term (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    academic_year_id UUID NOT NULL REFERENCES academic_year(id),
    name VARCHAR(50) NOT NULL,
    term_type VARCHAR(20) NOT NULL CHECK (term_type IN ('semester','quarter','trimester','full_year')),
    start_date DATE NOT NULL,
    end_date DATE NOT NULL
);

CREATE TABLE course (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    school_id UUID NOT NULL REFERENCES school(id),
    name VARCHAR(255) NOT NULL,
    subject VARCHAR(100),
    course_code VARCHAR(50)
);

CREATE TABLE teacher (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_account_id UUID REFERENCES user_account(id),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    school_id UUID NOT NULL REFERENCES school(id),
    email VARCHAR(255)
);

CREATE TABLE section (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id UUID NOT NULL REFERENCES course(id),
    term_id UUID NOT NULL REFERENCES term(id),
    teacher_id UUID NOT NULL REFERENCES teacher(id),
    period VARCHAR(50),
    room VARCHAR(50)
);

CREATE TABLE enrollment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id UUID NOT NULL REFERENCES student(id),
    section_id UUID NOT NULL REFERENCES section(id),
    enrolled_on DATE NOT NULL,
    dropped_on DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active','dropped','completed'))
);

CREATE TABLE assignment_category (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    section_id UUID NOT NULL REFERENCES section(id),
    name VARCHAR(100) NOT NULL,
    weight_pct NUMERIC(5,2) NOT NULL
);

CREATE TABLE assignment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    section_id UUID NOT NULL REFERENCES section(id),
    category_id UUID NOT NULL REFERENCES assignment_category(id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    points_possible NUMERIC(6,2) NOT NULL,
    assigned_date DATE,
    due_date DATE
);

CREATE TABLE assignment_grade (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id UUID NOT NULL REFERENCES assignment(id),
    student_id UUID NOT NULL REFERENCES student(id),
    points_earned NUMERIC(6,2),
    status VARCHAR(20) NOT NULL DEFAULT 'pending' CHECK (status IN ('graded','missing','late','excused','pending')),
    graded_at TIMESTAMP,
    teacher_comment TEXT,
    UNIQUE (assignment_id, student_id)
);

CREATE TABLE term_grade (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    student_id UUID NOT NULL REFERENCES student(id),
    section_id UUID NOT NULL REFERENCES section(id),
    term_id UUID NOT NULL REFERENCES term(id),
    final_pct NUMERIC(5,2),
    final_letter VARCHAR(5),
    gpa_points NUMERIC(3,2),
    posted_at TIMESTAMP,
    UNIQUE (student_id, section_id, term_id)
);

CREATE TABLE alert_rule (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    guardian_id UUID NOT NULL REFERENCES user_account(id),
    student_id UUID REFERENCES student(id),
    trigger_type VARCHAR(30) NOT NULL CHECK (trigger_type IN ('grade_below','missing_assignment','grade_drop_pct','report_card_posted')),
    threshold_value NUMERIC(6,2),
    channel VARCHAR(20) NOT NULL CHECK (channel IN ('email','sms','push','in_app')),
    active BOOLEAN NOT NULL DEFAULT true
);

CREATE TABLE notification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_account_id UUID NOT NULL REFERENCES user_account(id),
    alert_rule_id UUID REFERENCES alert_rule(id),
    student_id UUID REFERENCES student(id),
    message VARCHAR(500) NOT NULL,
    related_entity_type VARCHAR(50),
    related_entity_id UUID,
    read_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
