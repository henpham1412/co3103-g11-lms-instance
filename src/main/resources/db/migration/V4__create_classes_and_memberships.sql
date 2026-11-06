CREATE TABLE class (
    class_id UUID PRIMARY KEY,
    tutor_id UUID NOT NULL REFERENCES lms_user_access(user_id),
    class_code VARCHAR(30) NOT NULL,
    title VARCHAR(150) NOT NULL,
    description VARCHAR(2000),
    max_capacity INTEGER,
    enrollment_policy VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    thumbnail_url TEXT,
    prerequisites_description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_class_class_code UNIQUE (class_code),
    CONSTRAINT chk_class_max_capacity CHECK (max_capacity IS NULL OR max_capacity > 0),
    CONSTRAINT chk_class_enrollment_policy CHECK (enrollment_policy IN ('OPEN', 'APPROVAL_REQUIRED')),
    CONSTRAINT chk_class_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE TABLE class_member (
    class_member_id UUID PRIMARY KEY,
    class_id UUID NOT NULL REFERENCES class(class_id),
    student_id UUID NOT NULL REFERENCES lms_user_access(user_id),
    status VARCHAR(20) NOT NULL,
    requested_at TIMESTAMPTZ,
    joined_at TIMESTAMPTZ,
    CONSTRAINT uq_class_member_class_student UNIQUE (class_id, student_id),
    CONSTRAINT chk_class_member_status CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'INACTIVE'))
);

CREATE INDEX idx_class_tutor_id ON class(tutor_id);
CREATE INDEX idx_class_member_class_status ON class_member(class_id, status);
CREATE INDEX idx_class_member_student_status ON class_member(student_id, status);
