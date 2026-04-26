-- +goose Up
SELECT 'up SQL query';

CREATE TYPE generic_processing_status AS ENUM (
    'pending',
    'processing',
    'downstream_processing',
    'done',
    'failed'
);

CREATE TABLE users (
    id INT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    custom_instructions TEXT,
    ai_summary TEXT
);

CREATE TABLE sessions (
    id INT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    user_notes TEXT,
    transcription TEXT,
    ai_summary TEXT,

    status generic_processing_status NOT NULL DEFAULT 'processing'
);

CREATE TABLE recording_chunks (
    id INT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    session_id INT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    filename TEXT NOT NULL,

    transcription TEXT,

    status generic_processing_status NOT NULL DEFAULT 'pending'
);


CREATE TABLE speakers (
    id INT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    user_id INT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    name TEXT,
    embeddings JSONB,
    centroid INT REFERENCES speakers(id) ON DELETE SET NULL,
    
    ai_summary TEXT
);

CREATE TABLE segments (
    id INT PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    chunk_id INT NOT NULL REFERENCES recording_chunks(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    transcription TEXT,
    speaker_id INT REFERENCES speakers(id) ON DELETE SET NULL,
    local_speaker_label TEXT,
    
    time_start DOUBLE PRECISION NOT NULL,
    time_end DOUBLE PRECISION NOT NULL,

    processing_status generic_processing_status NOT NULL DEFAULT 'processing'
);

-- +goose Down
DROP TABLE IF EXISTS segments;
DROP TABLE IF EXISTS speakers;
DROP TABLE IF EXISTS recording_chunks;
DROP TABLE IF EXISTS sessions;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS schema_version;
DROP TYPE IF EXISTS session_status;
DROP TYPE IF EXISTS reconciliation_status;
DROP TYPE IF EXISTS generic_processing_status;
DROP TYPE IF EXISTS transcription_status;
SELECT 'down SQL query';
