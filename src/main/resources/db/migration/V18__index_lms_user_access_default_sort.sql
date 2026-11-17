CREATE INDEX idx_lms_user_access_created_at_user_id
    ON lms_user_access (created_at DESC, user_id ASC);
