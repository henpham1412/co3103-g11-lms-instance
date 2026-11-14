CREATE TABLE assignment (
    assignment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    activity_id UUID NOT NULL UNIQUE REFERENCES activity(activity_id),
    opening_time TIMESTAMPTZ,
    deadline TIMESTAMPTZ NOT NULL,
    allow_late BOOLEAN NOT NULL DEFAULT FALSE,
    allowed_file_types VARCHAR(1000),
    max_file_size_mb INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_assignment_window CHECK (opening_time IS NULL OR opening_time < deadline),
    CONSTRAINT chk_assignment_max_file_size CHECK (max_file_size_mb IS NULL OR max_file_size_mb > 0)
);

CREATE TABLE assignment_attachment (
    attachment_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id UUID NOT NULL REFERENCES assignment(assignment_id) ON DELETE CASCADE,
    file_name TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0)
);

CREATE INDEX idx_assignment_attachment_assignment ON assignment_attachment(assignment_id);

CREATE TABLE submission (
    submission_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id UUID NOT NULL REFERENCES assignment(assignment_id) ON DELETE CASCADE,
    class_member_id UUID NOT NULL REFERENCES class_member(class_member_id),
    file_name TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_late BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    score NUMERIC(5, 2),
    feedback TEXT,
    graded_by UUID REFERENCES lms_user_access(user_id),
    graded_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_submission_assignment_member UNIQUE (assignment_id, class_member_id),
    CONSTRAINT chk_submission_status CHECK (status IN ('SUBMITTED', 'GRADED')),
    CONSTRAINT chk_submission_score CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    CONSTRAINT chk_submission_grade_fields CHECK (
        (status = 'SUBMITTED' AND score IS NULL AND graded_by IS NULL AND graded_at IS NULL)
        OR (status = 'GRADED' AND score IS NOT NULL AND graded_by IS NOT NULL AND graded_at IS NOT NULL)
    )
);

CREATE INDEX idx_submission_assignment_status ON submission(assignment_id, status);
CREATE INDEX idx_submission_member ON submission(class_member_id);
