CREATE TABLE lms_user_access (
             user_id UUID PRIMARY KEY,
             full_name VARCHAR(150) NOT NULL,
             email VARCHAR(254) NOT NULL,
             lms_role VARCHAR(20) NOT NULL,
             access_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
             last_login_at TIMESTAMPTZ,
             created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
             updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

             CONSTRAINT chk_lms_user_access_role
                 CHECK (lms_role IN ('STUDENT', 'TUTOR', 'ADMINISTRATOR')),

             CONSTRAINT chk_lms_user_access_status
                 CHECK (access_status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE TABLE class (
    class_id UUID PRIMARY KEY,
    tutor_id UUID NOT NULL REFERENCES lms_user_access(user_id),
    class_code VARCHAR(30) NOT NULL UNIQUE,
    title VARCHAR(150) NOT NULL,
    description VARCHAR(2000),
    max_capacity INTEGER,
    enrollment_policy VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    thumbnail_url TEXT,
    prerequisites_description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_class_max_capacity
        CHECK (max_capacity IS NULL OR max_capacity > 0),

    CONSTRAINT chk_class_enrollment_policy
        CHECK (enrollment_policy IN ('OPEN', 'APPROVAL_REQUIRED')),

    CONSTRAINT chk_class_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE TABLE class_member (
    class_member_id UUID PRIMARY KEY,
    class_id UUID NOT NULL REFERENCES class(class_id),
    student_id UUID NOT NULL REFERENCES lms_user_access(user_id),
    status VARCHAR(20) NOT NULL,
    requested_at TIMESTAMPTZ,
    joined_at TIMESTAMPTZ,

    CONSTRAINT uq_class_member_class_student
        UNIQUE (class_id, student_id),

    CONSTRAINT chk_class_member_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'INACTIVE'))
);
