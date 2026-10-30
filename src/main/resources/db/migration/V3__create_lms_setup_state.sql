-- Email is a mutable identity projection, never an LMS identity key.
ALTER TABLE lms_user_access DROP CONSTRAINT uq_lms_user_access_email;

CREATE TABLE lms_setup_state (
    id VARCHAR(32) PRIMARY KEY,
    is_completed BOOLEAN NOT NULL DEFAULT FALSE,
    completed_at TIMESTAMP WITH TIME ZONE,
    bootstrapped_by UUID,
    CONSTRAINT chk_lms_setup_state_singleton CHECK (id = 'GLOBAL'),
    CONSTRAINT fk_lms_setup_state_user FOREIGN KEY (bootstrapped_by)
        REFERENCES lms_user_access(user_id) ON DELETE SET NULL
);

-- Provision the lock target before any application can attempt bootstrap.
INSERT INTO lms_setup_state (id, is_completed) VALUES ('GLOBAL', FALSE);
