-- Requires class(class_id UUID PRIMARY KEY).
-- Java difficulty conversion must use explicitly agreed numeric codes (1, 3, 5).
-- No Enum ordinal mapping is permitted.

CREATE TABLE skill_topics (
          skill_topic_id UUID NOT NULL,
          class_id UUID NOT NULL,
          name VARCHAR(150) NOT NULL,
          description TEXT,
          difficulty_level SMALLINT NOT NULL,
          created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

          CONSTRAINT pk_skill_topics
              PRIMARY KEY (skill_topic_id),

          CONSTRAINT uq_skill_topics_class_name
              UNIQUE (class_id, name),

          CONSTRAINT chk_skill_topics_difficulty_range
              CHECK (difficulty_level IN (1, 3, 5)),

          CONSTRAINT fk_skill_topics_class
              FOREIGN KEY (class_id)
                  REFERENCES class(class_id)
                  ON UPDATE RESTRICT
                  ON DELETE RESTRICT
);

CREATE TABLE skill_prerequisites (
         skill_topic_id UUID NOT NULL,
         prerequisite_skill_topic_id UUID NOT NULL,

         CONSTRAINT pk_skill_prerequisites
             PRIMARY KEY (skill_topic_id, prerequisite_skill_topic_id),

         CONSTRAINT fk_skill_prerequisites_target
             FOREIGN KEY (skill_topic_id)
                 REFERENCES skill_topics(skill_topic_id)
                 ON UPDATE RESTRICT
                 ON DELETE RESTRICT,

         CONSTRAINT fk_skill_prerequisites_prerequisite
             FOREIGN KEY (prerequisite_skill_topic_id)
                 REFERENCES skill_topics(skill_topic_id)
                 ON UPDATE RESTRICT
                 ON DELETE RESTRICT,

         CONSTRAINT chk_skill_prerequisites_not_self
             CHECK (skill_topic_id <> prerequisite_skill_topic_id)
);

CREATE INDEX idx_skill_prerequisites_prerequisite_target
ON skill_prerequisites (
                prerequisite_skill_topic_id,
                skill_topic_id
);
