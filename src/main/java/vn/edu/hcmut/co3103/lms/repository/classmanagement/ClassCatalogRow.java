package vn.edu.hcmut.co3103.lms.repository.classmanagement;

import java.util.UUID;
import java.time.Instant;

public interface ClassCatalogRow {

    UUID getClassId();

    String getClassCode();

    String getTitle();

    String getDescription();

    String getPrerequisitesDescription();

    String getThumbnailUrl();

    String getEnrollmentPolicy();

    String getStatus();

    UUID getTutorId();

    String getTutorName();

    Long getActiveMemberCount();

    Integer getMaxCapacity();

    Instant getCreatedAt();

    Instant getUpdatedAt();
}
