## Audio Thing

Rust gRPC server plus Python workers for transcription and diarization.

ulti stage producer consumer pipeline with Postgres and gRPC

### Components
- `server/`: Rust gRPC server that ingests audio and writes DB records.
- `server/pyworkers/`: Python workers for transcription (Whisper) and diarization (pyannote).
- `proto/`: gRPC definitions.
- `migrations/`: Postgres schema (goose).
- `androidapp`: Basic audio sampling grpc client.

### Requirements
- Postgres running locally.
- Python env for `server/pyworkers`.
- GPU recommended for Whisper/pyannote.

### Quick start
1. DB migrations:
   - `make db-up`
2. From `server/`:
   - `make run`

### Notes
- DB env vars: `POSTGRESS_USER`, `POSTGRESS_PASSWORD`.
- Diarizer needs `HF_TOKEN` for the pyannote model.
