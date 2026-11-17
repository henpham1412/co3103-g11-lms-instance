package vn.edu.hcmut.co3103.lms.service.moderation;

import org.springframework.stereotype.Component;
import vn.edu.hcmut.co3103.lms.dto.response.ModerationTargetResponse;
import vn.edu.hcmut.co3103.lms.entity.ContentReport;
import vn.edu.hcmut.co3103.lms.repository.classmanagement.ClassRepository;
import vn.edu.hcmut.co3103.lms.repository.interaction.DiscussionRepository;
import vn.edu.hcmut.co3103.lms.repository.learningcontent.MaterialRepository;
import vn.edu.hcmut.co3103.lms.service.learningcontent.ChapterService;
import java.net.URI;
import java.util.UUID;

/** Called only after a report has been loaded by the Administrator moderation boundary. */
@Component
public class ModerationTargetReader {
    private final DiscussionRepository discussions;
    private final MaterialRepository materials;
    private final ClassRepository classes;
    private final ChapterService chapters;

    public ModerationTargetReader(DiscussionRepository discussions, MaterialRepository materials,
                                  ClassRepository classes, ChapterService chapters) {
        this.discussions = discussions; this.materials = materials;
        this.classes = classes; this.chapters = chapters;
    }

    public ModerationTargetResponse inspect(ContentReport report) {
        if (report.getContentType() == ContentReport.ContentType.DISCUSSION) {
            return discussions.findById(report.getContentId()).map(d -> new ModerationTargetResponse(
                    d.getDiscussionId(), d.getClassId(), d.getLessonId(), d.getAuthorId(), d.getParentId(),
                    d.getTitle(), d.getContent(), null, null, classStatus(d.getClassId()), null, null)).orElse(null);
        }
        return materials.findById(report.getContentId()).map(m -> {
            UUID classId = materials.findClassIdByMaterialId(m.getMaterialId()).orElse(null);
            String url = null;
            Boolean available = null;
            if (m.getFileUrl() != null) {
                try {
                    URI external = URI.create(m.getFileUrl());
                    if (("https".equalsIgnoreCase(external.getScheme()) || "http".equalsIgnoreCase(external.getScheme()))
                            && external.getHost() != null && external.getUserInfo() == null) url = external.toString();
                } catch (IllegalArgumentException invalidStoredUrl) { /* Never expose physical or malformed paths. */ }
            }
            if (url == null && (m.getMaterialType() == vn.edu.hcmut.co3103.lms.entity.Material.MaterialType.DOCUMENT
                    || m.getMaterialType() == vn.edu.hcmut.co3103.lms.entity.Material.MaterialType.VIDEO)) {
                available = chapters.findModerationMaterialFile(m.getMaterialId()).isPresent();
                if (available) url = "/api/v1/admin/content-reports/" + report.getReportId() + "/target/file";
            }
            return new ModerationTargetResponse(m.getMaterialId(), classId, m.getLessonId(), null, null,
                    m.getTitle(), m.getContent(), m.getMaterialType().name(), m.getStatus().name(),
                    classStatus(classId), url, available);
        }).orElse(null);
    }

    private String classStatus(UUID id) {
        return id == null ? null : classes.findById(id).map(c -> c.getStatus().name()).orElse(null);
    }
}
