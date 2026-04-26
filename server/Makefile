SHELL := /bin/bash

ROOT_DIR := ..
TARGET_DIR := $(ROOT_DIR)/target
WHEEL_DIR := $(TARGET_DIR)/wheels

PYWORKERS_DIR := pyworkers

PYWORKERS_PYTHON := $(PYWORKERS_DIR)/.venv/bin/python


.PHONY: help server transcriber diariser maturin-build sync-python build run clean_db

help:
	@printf '%s\n' \
		'server         Run the Rust server' \
		'transcriber    Run the transcription worker' \
		'diariser       Run the diarisation worker' \
		'maturin-build  Build the Rust wheel with maturin' \
		'sync-python    Refresh the pyworkers environment' \
		'build          Build the wheel and sync pyworkers' \
		'run            Start all three processes together' \
		'clean_db       Truncate and dropthe audio database tables'

server:
	cargo run

transcriber:
	$(PYWORKERS_PYTHON) -m pyworkers.main --target transcriber

diariser:
	$(PYWORKERS_PYTHON) -m pyworkers.main --target diariser

maturin-build:
	maturin build --release --out $(WHEEL_DIR)

sync-python:
	cd $(PYWORKERS_DIR) && rm -f uv.lock && uv sync

build: maturin-build sync-python

run: build
	@set -euo pipefail; \
	cargo run & \
	server_pid=$$!; \
	$(PYWORKERS_PYTHON) -m pyworkers.main --target transcriber & \
	transcriber_pid=$$!; \
	$(PYWORKERS_PYTHON) -m pyworkers.main --target diariser & \
	diariser_pid=$$!; \
	cleanup() { \
		kill $$server_pid $$transcriber_pid $$diariser_pid 2>/dev/null || true; \
	}; \
	trap cleanup INT TERM EXIT; \
	wait -n $$server_pid $$transcriber_pid $$diariser_pid; \
	status=$$?; \
	echo "One process exited (status=$$status), stopping the others..."; \
	exit $$status


clean_db:
# audio db
	docker exec -it postgress psql -U app -c "\c audio" -c "TRUNCATE TABLE sessions, transcriptions RESTART IDENTITY CASCADE;"