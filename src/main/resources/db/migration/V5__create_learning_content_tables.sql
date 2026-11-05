CREATE TABLE chapter (
    chapter_id UUID PRIMARY KEY,
    class_id UUID NOT NULL REFERENCES class(class_id),
    title TEXT NOT NULL,
    description TEXT,
    sequence_order INTEGER NOT NULL CHECK (sequence_order >= 0),
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED'
        CHECK (status IN ('PUBLISHED', 'HIDDEN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chapter_class_order ON chapter(class_id, sequence_order);

CREATE TABLE lesson (
    lesson_id UUID PRIMARY KEY,
    chapter_id UUID NOT NULL REFERENCES chapter(chapter_id),
    title TEXT NOT NULL,
    description TEXT,
    estimated_duration INTEGER
        CHECK (estimated_duration IS NULL OR estimated_duration BETWEEN 0 AND 1440),
    sequence_order INTEGER NOT NULL CHECK (sequence_order >= 0),
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED'
        CHECK (status IN ('PUBLISHED', 'HIDDEN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lesson_chapter_order ON lesson(chapter_id, sequence_order);

CREATE TABLE material (
    material_id UUID PRIMARY KEY,
    lesson_id UUID NOT NULL REFERENCES lesson(lesson_id),
    title TEXT NOT NULL,
    material_type VARCHAR(20) NOT NULL
        CHECK (material_type IN ('DOCUMENT', 'VIDEO', 'TEXT', 'URL')),
    file_url TEXT,
    content TEXT,
    sequence_order INTEGER NOT NULL CHECK (sequence_order >= 0),
    status VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED'
        CHECK (status IN ('PUBLISHED', 'HIDDEN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_material_payload CHECK (
        (material_type = 'TEXT' AND content IS NOT NULL)
        OR (material_type IN ('DOCUMENT', 'VIDEO', 'URL') AND file_url IS NOT NULL)
    )
);

CREATE INDEX idx_material_lesson_order ON material(lesson_id, sequence_order);
