package vn.edu.hcmut.co3103.lms.repository.moderation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.edu.hcmut.co3103.lms.entity.ContentReport;

import java.util.Collection;
import java.util.UUID;

public interface ContentReportRepository extends JpaRepository<ContentReport, UUID> {

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