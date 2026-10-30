package vn.edu.hcmut.co3103.lms.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "material")
public class Material {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "material_id", nullable = false)
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id", nullable = false)
    private Lesson lesson;

    @Size(max = 150)
    @NotNull
    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Size(max = 20)
    @NotNull
    @Column(name = "material_type", nullable = false, length = 20)
    private String materialType;

    @Size(max = 2083)
    @Column(name = "file_url", length = 2083)
    private String fileUrl;

    @Column(name = "content", length = Integer.MAX_VALUE)
    private String content;

    @NotNull
    @Column(name = "sequence_order", nullable = false)
    private Integer sequenceOrder;

    @Size(max = 20)
    @NotNull
    @ColumnDefault("'HIDDEN'")
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @NotNull
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;


}
