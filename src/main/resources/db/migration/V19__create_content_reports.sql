-- Content reporting (DOC-04 section 2.5.2). The target is polymorphic: content_type plus
-- content_id identify a Discussion or a Material, and DOC-04 section 3.6.2 requires the
-- application to validate that association instead of a conventional foreign key.
CREATE TABLE content_report (
    report_id UUID PRIMARY KEY,
    reporter_id UUID NOT NULL REFERENCES lms_user_access(user_id),
    content_type VARCHAR(20) NOT NULL,
    content_id UUID NOT NULL,
    reason VARCHAR(20) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewed_by UUID REFERENCES lms_user_access(user_id),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_content_report_type CHECK (content_type IN ('DISCUSSION', 'MATERIAL')),
    CONSTRAINT chk_content_report_reason CHECK (reason IN ('SPAM', 'HARASSMENT', 'INAPPROPRIATE', 'OTHER')),
    CONSTRAINT chk_content_report_status CHECK (status IN ('PENDING', 'REVIEWED', 'RESOLVED', 'DISMISSED')),
    CONSTRAINT chk_content_report_review CHECK (
        (status = 'PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL)
        OR (status <> 'PENDING' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
    ),
    CONSTRAINT uq_content_report_reporter_target UNIQUE (reporter_id, content_type, content_id)
);

CREATE INDEX idx_content_report_status ON content_report(status, created_at);
CREATE INDEX idx_content_report_target ON content_report(content_type, content_id);
CREATE INDEX idx_content_report_reporter ON content_report(reporter_id);