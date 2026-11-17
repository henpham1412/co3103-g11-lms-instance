-- Branch allocation checked against main V1-V13 and open LMS-13 V14-V16.
-- Shared rollout MUST apply LMS-13 V14-V16 first and re-check shared history.
-- Derived state only: no changes to canonical assessment/progress records.
CREATE TABLE learning_signal (
    learning_signal_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_member_id UUID NOT NULL REFERENCES class_member(class_member_id) ON DELETE CASCADE,
    skill_topic_id UUID NOT NULL REFERENCES skill_topics(skill_topic_id) ON DELETE CASCADE,
    signal_type VARCHAR(30) NOT NULL,
    signal_value NUMERIC NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_id UUID NOT NULL,
    CONSTRAINT uq_learning_signal_source UNIQUE
        (class_member_id, skill_topic_id, signal_type, source_type, source_id),
    CONSTRAINT chk_learning_signal_source CHECK (
        (signal_type = 'QUIZ_RESULT' AND source_type = 'QUIZ_ATTEMPT') OR
        (signal_type = 'ASSIGNMENT_RESULT' AND source_type = 'SUBMISSION') OR
        (signal_type = 'LESSON_COMPLETION' AND source_type = 'COMPLETION_MARK')),
    CONSTRAINT chk_learning_signal_value CHECK (
        (signal_type = 'LESSON_COMPLETION' AND signal_value = 1) OR
        (signal_type IN ('QUIZ_RESULT', 'ASSIGNMENT_RESULT') AND signal_value BETWEEN 0 AND 100))
);

CREATE TABLE learner_mastery (
    learner_mastery_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    class_member_id UUID NOT NULL REFERENCES class_member(class_member_id) ON DELETE CASCADE,
    skill_topic_id UUID NOT NULL REFERENCES skill_topics(skill_topic_id) ON DELETE CASCADE,
    mastery_level VARCHAR(20) NOT NULL,
    mastery_score NUMERIC NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_learner_mastery_member_topic UNIQUE (class_member_id, skill_topic_id),
    CONSTRAINT chk_learner_mastery_score CHECK (mastery_score BETWEEN 0 AND 100),
    CONSTRAINT chk_learner_mastery_level CHECK (
        (mastery_level = 'WEAK' AND mastery_score < 50) OR
        (mastery_level = 'DEVELOPING' AND mastery_score >= 50 AND mastery_score < 80) OR
        (mastery_level = 'SUFFICIENT' AND mastery_score >= 80))
);
