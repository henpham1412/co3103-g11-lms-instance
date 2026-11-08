CREATE TABLE completion_mark (
    mark_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_member_id UUID NOT NULL REFERENCES class_member(class_member_id),
    lesson_id UUID NOT NULL REFERENCES lesson(lesson_id) ON DELETE CASCADE,
    marked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_completion_mark_member_lesson UNIQUE (class_member_id, lesson_id)
);

CREATE INDEX ix_completion_mark_class_member
    ON completion_mark(class_member_id);
