CREATE TABLE notification (
    notification_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id UUID NOT NULL REFERENCES lms_user_access(user_id),
    notification_type VARCHAR(32) NOT NULL,
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    target_url TEXT,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMPTZ,
    source_event_key TEXT NOT NULL,
    CONSTRAINT chk_notification_read_state CHECK (is_read = (read_at IS NOT NULL)),
    CONSTRAINT uq_notification_recipient_event UNIQUE (recipient_id, notification_type, source_event_key)
);

CREATE INDEX idx_notification_inbox ON notification (recipient_id, created_at DESC, notification_id DESC);
CREATE INDEX idx_notification_inbox_read ON notification (recipient_id, is_read, created_at DESC, notification_id DESC);
CREATE INDEX idx_notification_retention ON notification (created_at);
CREATE INDEX idx_quiz_deadline ON quiz (deadline);
CREATE INDEX idx_assignment_deadline ON assignment (deadline);
