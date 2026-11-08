ALTER TABLE completion_mark
    DROP CONSTRAINT completion_mark_lesson_id_fkey,
    ADD CONSTRAINT fk_completion_mark_lesson
        FOREIGN KEY (lesson_id) REFERENCES lesson(lesson_id);
