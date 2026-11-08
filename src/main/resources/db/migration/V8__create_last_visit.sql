CREATE TABLE last_visit (
    class_member_id UUID PRIMARY KEY REFERENCES class_member(class_member_id),
    lesson_id UUID NOT NULL REFERENCES lesson(lesson_id) ON DELETE CASCADE,
    visited_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_last_visit_lesson
    ON last_visit(lesson_id);
