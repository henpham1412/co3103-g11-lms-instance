CREATE TABLE quiz (
    quiz_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    activity_id UUID NOT NULL UNIQUE REFERENCES activity(activity_id),
    time_limit_minutes INTEGER NOT NULL,
    attempts_allowed INTEGER NOT NULL DEFAULT 1,
    grading_method VARCHAR(20) NOT NULL DEFAULT 'HIGHEST',
    opening_time TIMESTAMPTZ,
    deadline TIMESTAMPTZ NOT NULL,
    required_score NUMERIC(5, 2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_quiz_time_limit
        CHECK (time_limit_minutes > 0),
    CONSTRAINT chk_quiz_attempts_allowed
        CHECK (attempts_allowed >= 1),
    CONSTRAINT chk_quiz_grading_method
        CHECK (grading_method IN ('HIGHEST', 'AVERAGE', 'LAST')),
    CONSTRAINT chk_quiz_availability_window
        CHECK (opening_time IS NULL OR opening_time < deadline),
    CONSTRAINT chk_quiz_required_score
        CHECK (required_score IS NULL OR required_score BETWEEN 0 AND 100)
);

CREATE TABLE question (
    question_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quiz_id UUID NOT NULL REFERENCES quiz(quiz_id),
    question_type VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    points_awarded NUMERIC(10, 2) NOT NULL,
    points_deducted NUMERIC(10, 2) NOT NULL DEFAULT 0,
    order_index INTEGER NOT NULL,

    CONSTRAINT chk_question_type
        CHECK (question_type IN ('MCQ_SINGLE', 'MCQ_MULTIPLE', 'TRUE_FALSE')),
    CONSTRAINT chk_question_points_deducted
        CHECK (points_deducted >= 0),
    CONSTRAINT chk_question_order_index
        CHECK (order_index >= 0),
    CONSTRAINT uq_question_quiz_order
        UNIQUE (quiz_id, order_index)
);

CREATE TABLE question_option (
    option_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id UUID NOT NULL REFERENCES question(question_id),
    content TEXT NOT NULL,
    is_correct BOOLEAN NOT NULL,
    order_index INTEGER NOT NULL,

    CONSTRAINT chk_question_option_order_index
        CHECK (order_index >= 0),
    CONSTRAINT uq_question_option_question_order
        UNIQUE (question_id, order_index)
);

CREATE TABLE quiz_attempt (
    attempt_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    quiz_id UUID NOT NULL REFERENCES quiz(quiz_id),
    class_member_id UUID NOT NULL REFERENCES class_member(class_member_id),
    attempt_number INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMPTZ,
    auto_submitted BOOLEAN NOT NULL DEFAULT FALSE,
    score NUMERIC(5, 2),
    graded_at TIMESTAMPTZ,

    CONSTRAINT uq_quiz_attempt_number
        UNIQUE (quiz_id, class_member_id, attempt_number),
    CONSTRAINT chk_quiz_attempt_number
        CHECK (attempt_number >= 1),
    CONSTRAINT chk_quiz_attempt_status
        CHECK (status IN ('IN_PROGRESS', 'SUBMITTED')),
    CONSTRAINT chk_quiz_attempt_score
        CHECK (score IS NULL OR score BETWEEN 0 AND 100)
);

CREATE TABLE attempt_answer (
    answer_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id UUID NOT NULL REFERENCES quiz_attempt(attempt_id),
    question_id UUID NOT NULL REFERENCES question(question_id) ON DELETE RESTRICT,
    is_correct BOOLEAN NOT NULL,
    points_earned NUMERIC(10, 2) NOT NULL,

    CONSTRAINT uq_attempt_answer_question
        UNIQUE (attempt_id, question_id)
);

CREATE TABLE attempt_answer_option (
    answer_id UUID NOT NULL REFERENCES attempt_answer(answer_id) ON DELETE CASCADE,
    option_id UUID NOT NULL REFERENCES question_option(option_id) ON DELETE RESTRICT,

    PRIMARY KEY (answer_id, option_id)
);
