package vn.edu.hcmut.co3103.lms.service.adaptive.integration;

import java.util.UUID;

/** Required LMS-2/LMS-3 integration; no permissive default implementation. */
public interface ClassAccess {

    /**
     * Resolve an existing class and require permitted local read access.
     * Adapter business failures use AppException with CLASS_NOT_FOUND or CLASS_ACCESS_DENIED.
     */
    void requireReadAccess(UUID classId);

    /**
     * Within the caller's database transaction, lock the existing Class row for
     * writing until completion, then require active local owning-Tutor access and
     * a writable class lifecycle state. All class graph/association writers must
     * use this same lock. Missing class/access/lifecycle failures use AppException
     * with CLASS_NOT_FOUND, CLASS_ACCESS_DENIED or RESOURCE_STATE_CONFLICT.
     */
    void lockForWrite(UUID classId);
}
