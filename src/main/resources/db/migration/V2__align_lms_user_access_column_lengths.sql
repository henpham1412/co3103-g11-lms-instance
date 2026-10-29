ALTER TABLE lms_user_access
    ALTER COLUMN email TYPE VARCHAR(255),
    ALTER COLUMN lms_role TYPE VARCHAR(30),
    ALTER COLUMN access_status TYPE VARCHAR(30);

ALTER TABLE lms_user_access
    ADD CONSTRAINT uq_lms_user_access_email UNIQUE (email);
