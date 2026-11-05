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