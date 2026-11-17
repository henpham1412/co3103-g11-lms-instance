package vn.edu.hcmut.co3103.lms.repository.moderation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.hcmut.co3103.lms.entity.ContentReport;

import java.util.Collection;
import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

public interface ContentReportRepository extends JpaRepository<ContentReport, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ContentReport r where r.reportId = :id")
    Optional<ContentReport> findForReview(@Param("id") UUID id);

    boolean existsByReporterIdAndContentTypeAndContentId(UUID reporterId, ContentReport.ContentType contentType,
                                                         UUID contentId);

    boolean existsByContentTypeAndContentIdAndStatusIn(ContentReport.ContentType contentType, UUID contentId,
                                                       Collection<ContentReport.ReportStatus> statuses);

    @Query("select r from ContentReport r where (:status is null or r.status = :status) "
            + "and (:contentType is null or r.contentType = :contentType)")
    Page<ContentReport> search(@Param("status") ContentReport.ReportStatus status,
                               @Param("contentType") ContentReport.ContentType contentType,
                               Pageable pageable);
}
