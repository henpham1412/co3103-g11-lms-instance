CREATE TABLE chapter (
    chapter_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_id UUID NOT NULL REFERENCES class(class_id),
    title VARCHAR(150) NOT NULL,
    description TEXT,
    sequence_order INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'HIDDEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_chapter_sequence_order
        CHECK (sequence_order >= 0),
    CONSTRAINT chk_chapter_status
        CHECK (status IN ('PUBLISHED', 'HIDDEN')),
    CONSTRAINT uq_chapter_class_sequence
        UNIQUE (class_id, sequence_order)
);

CREATE TABLE lesson (
    lesson_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chapter_id INTEGER NOT NULL REFERENCES chapter(chapter_id),
    title VARCHAR(150) NOT NULL,
    description TEXT,
    estimated_duration INTEGER NOT NULL DEFAULT 0,
    sequence_order INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'HIDDEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_lesson_duration
        CHECK (estimated_duration BETWEEN 0 AND 1440),
    CONSTRAINT chk_lesson_sequence_order
        CHECK (sequence_order >= 0),
    CONSTRAINT chk_lesson_status
        CHECK (status IN ('PUBLISHED', 'HIDDEN')),
    CONSTRAINT uq_lesson_chapter_sequence
        UNIQUE (chapter_id, sequence_order)
);

CREATE TABLE activity (
    activity_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lesson_id INTEGER NOT NULL REFERENCES lesson(lesson_id),
    activity_type VARCHAR(20) NOT NULL,
    title VARCHAR(150) NOT NULL,
    description TEXT,
    sequence_order INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'HIDDEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_activity_type
        CHECK (activity_type IN ('QUIZ', 'ASSIGNMENT')),
    CONSTRAINT chk_activity_sequence_order
        CHECK (sequence_order >= 0),
    CONSTRAINT chk_activity_status
        CHECK (status IN ('PUBLISHED', 'HIDDEN')),
    CONSTRAINT uq_activity_lesson_sequence
        UNIQUE (lesson_id, sequence_order)
);

CREATE TABLE material (
    material_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lesson_id INTEGER NOT NULL REFERENCES lesson(lesson_id),
    title VARCHAR(150) NOT NULL,
    material_type VARCHAR(20) NOT NULL,
    file_url VARCHAR(2083),
    content TEXT,
    sequence_order INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'HIDDEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_material_type
        CHECK (material_type IN ('DOCUMENT', 'VIDEO', 'TEXT', 'URL')),
    CONSTRAINT chk_material_sequence_order
        CHECK (sequence_order >= 0),
    CONSTRAINT chk_material_status
        CHECK (status IN ('PUBLISHED', 'HIDDEN')),
    CONSTRAINT chk_material_content
        CHECK (
            (material_type IN ('DOCUMENT', 'VIDEO', 'URL') AND file_url IS NOT NULL)
            OR (material_type = 'TEXT' AND content IS NOT NULL)
        ),
    CONSTRAINT uq_material_lesson_sequence
        UNIQUE (lesson_id, sequence_order)
);
