-- Forward-only correction: preserve previously published V2-V5 definitions.
-- Requires their upstream UUID parent tables and successful preceding migrations.
-- Deliberately fails if existing rows contain NULL difficulty_level: an operator
-- must supply reviewed values rather than silently assigning an arbitrary level.
ALTER TABLE skill_topics
    ALTER COLUMN difficulty_level SET NOT NULL;
