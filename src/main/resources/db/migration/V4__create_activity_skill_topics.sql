-- Requires:
--   skill_topics(skill_topic_id UUID PRIMARY KEY)
--   activities(activity_id UUID PRIMARY KEY)

CREATE TABLE activity_skill_topics (
           activity_id UUID NOT NULL,
           skill_topic_id UUID NOT NULL,

           CONSTRAINT pk_activity_skill_topics
               PRIMARY KEY (activity_id, skill_topic_id),

           CONSTRAINT fk_activity_skill_topics_activity
               FOREIGN KEY (activity_id)
                   REFERENCES activities(activity_id)
                   ON UPDATE RESTRICT
                   ON DELETE RESTRICT,

           CONSTRAINT fk_activity_skill_topics_topic
               FOREIGN KEY (skill_topic_id)
                   REFERENCES skill_topics(skill_topic_id)
                   ON UPDATE RESTRICT
                   ON DELETE RESTRICT
);

CREATE INDEX idx_activity_skill_topics_topic_activity
    ON activity_skill_topics (skill_topic_id, activity_id);