package vn.edu.hcmut.co3103.lms.repository.classmanagement;

/** Detail view projection: catalog columns plus the LMS-local instructor email. */
public interface ClassDetailRow extends ClassCatalogRow {

    String getTutorEmail();
}
