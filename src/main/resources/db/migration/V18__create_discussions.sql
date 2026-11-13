-- Learning-related discussions (DOC-04 section 2.6.1). A root thread carries a title and no
-- parent; a reply points at its parent and stays in the same class and lesson context.
CREATE TABLE discussion (
    discussion_id UUID PRIMARY KEY,
    class_id UUID NOT NULL REFERENCES class(class_id),
    lesson_id UUID REFERENCES lesson(lesson_id),
    author_id UUID NOT NULL REFERENCES lms_user_access(user_id),
    parent_id UUID REFERENCES discussion(discussion_id),
    title VARCHAR(255),
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_discussion_root_title CHECK (
        parent_id IS NOT NULL OR (title IS NOT NULL AND length(btrim(title)) > 0)
    ),
    CONSTRAINT chk_discussion_content CHECK (length(btrim(content)) > 0)
);

CREATE INDEX idx_discussion_class_parent ON discussion(class_id, parent_id);
CREATE INDEX idx_discussion_lesson ON discussion(lesson_id);
CREATE INDEX idx_discussion_parent ON discussion(parent_id);
CREATE INDEX idx_discussion_author ON discussion(author_id);