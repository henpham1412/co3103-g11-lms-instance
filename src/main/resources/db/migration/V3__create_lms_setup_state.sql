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
-- Seal existing installations now so later administrator removal cannot reopen setup.
INSERT INTO lms_setup_state (id, is_completed, completed_at, bootstrapped_by)
SELECT 'GLOBAL',
       existing_admin.user_id IS NOT NULL,
       CASE WHEN existing_admin.user_id IS NOT NULL
            THEN CURRENT_TIMESTAMP ELSE NULL END,
       existing_admin.user_id
FROM (SELECT 1 AS singleton) AS seed
LEFT JOIN (
    SELECT user_id
    FROM lms_user_access
    WHERE lms_role = 'ADMINISTRATOR'
    ORDER BY created_at ASC, user_id ASC
    FETCH FIRST 1 ROW ONLY
) AS existing_admin ON TRUE;
