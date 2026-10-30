-- Requires:
--   skill_topics(skill_topic_id UUID PRIMARY KEY)
--   questions(question_id UUID PRIMARY KEY)
--
-- This is the single shared question-topic table for LMS-8 and LMS-5.
-- Its creation must have one migration owner.

CREATE TABLE question_skill_topics (
           question_id UUID NOT NULL,
           skill_topic_id UUID NOT NULL,

           CONSTRAINT pk_question_skill_topics
               PRIMARY KEY (question_id, skill_topic_id),

           CONSTRAINT fk_question_skill_topics_question
               FOREIGN KEY (question_id)
                   REFERENCES questions(question_id)
                   ON UPDATE RESTRICT
                   ON DELETE RESTRICT,

           CONSTRAINT fk_question_skill_topics_topic
               FOREIGN KEY (skill_topic_id)
                   REFERENCES skill_topics(skill_topic_id)
                   ON UPDATE RESTRICT
                   ON DELETE RESTRICT
);

CREATE INDEX idx_question_skill_topics_topic_question
    ON question_skill_topics (skill_topic_id, question_id);