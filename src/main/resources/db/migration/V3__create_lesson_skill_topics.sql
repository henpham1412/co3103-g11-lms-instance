-- Requires:
--   skill_topics(skill_topic_id UUID PRIMARY KEY)
--   lessons(lesson_id UUID PRIMARY KEY)

CREATE TABLE lesson_skill_topics (
             lesson_id UUID NOT NULL,
             skill_topic_id UUID NOT NULL,

             CONSTRAINT pk_lesson_skill_topics
                 PRIMARY KEY (lesson_id, skill_topic_id),

             CONSTRAINT fk_lesson_skill_topics_lesson
                 FOREIGN KEY (lesson_id)
                     REFERENCES lessons(lesson_id)
                     ON UPDATE RESTRICT
                     ON DELETE RESTRICT,

             CONSTRAINT fk_lesson_skill_topics_topic
                 FOREIGN KEY (skill_topic_id)
                     REFERENCES skill_topics(skill_topic_id)
                     ON UPDATE RESTRICT
                     ON DELETE RESTRICT
);

CREATE INDEX idx_lesson_skill_topics_topic_lesson
    ON lesson_skill_topics (skill_topic_id, lesson_id);