-- Issuance watermarks are independent of local reception/administration timestamps.
-- Exact epoch seconds preserve the nanosecond precision of decoded JWT iat values.
-- Existing issuance is unknown: initialize on the first trusted token after upgrade.
ALTER TABLE lms_user_access
    ADD COLUMN last_synced_profile_iat NUMERIC(21, 9);
ALTER TABLE lms_user_access
    ADD COLUMN last_login_token_iat NUMERIC(21, 9);
