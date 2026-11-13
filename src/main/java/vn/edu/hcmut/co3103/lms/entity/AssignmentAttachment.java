package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "assignment_attachment")
public class AssignmentAttachment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "attachment_id", nullable = false, updatable = false)
    private UUID attachmentId;
    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;
    @Column(name = "file_name", nullable = false)
    private String fileName;
    @Column(name = "storage_path", nullable = false)
    private String storagePath;
    @Column(name = "mime_type", nullable = false)
    private String mimeType;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    protected AssignmentAttachment() { }

    public AssignmentAttachment(UUID assignmentId, String fileName, String storagePath,
                                String mimeType, long sizeBytes) {
        this.assignmentId = assignmentId;
        this.fileName = fileName;
        this.storagePath = storagePath;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
    }

    public UUID getAttachmentId() { return attachmentId; }
    public UUID getAssignmentId() { return assignmentId; }
    public String getFileName() { return fileName; }
    public String getStoragePath() { return storagePath; }
    public String getMimeType() { return mimeType; }
    public long getSizeBytes() { return sizeBytes; }
}
