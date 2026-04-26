import asyncio
import logging
from pathlib import Path
from asyncio import Queue
from typing import Any, Awaitable, Callable
import asyncpg
import numpy as np
import torch
from wyoming.client import AsyncClient
from wyoming.audio import AudioChunk, AudioStart, AudioStop
from wyoming.asr import Transcript
from faster_whisper import WhisperModel
import aiofiles

from .common import BATCH_SIZE, MAX_SPAWN, SAMPLE_RATE
from .db import PG_DSN, PostgresManager

WIDTH = 2
CHANNELS = 1


"""
def load_pcm(path: Path):
    audio = np.fromfile(path, dtype=np.int16)
    return audio.astype("float32") / 32768.0
"""


async def load_pcm_audio(file_path, start_second: float, end_second: float) -> np.ndarray:
    audio = np.fromfile(file_path, dtype=np.int16)
    start_sample = int(start_second * SAMPLE_RATE)
    end_sample = int(end_second * SAMPLE_RATE)
    audio_segment = audio[start_sample:end_sample]
    return audio_segment.astype("float32") / 32768.0

async def rows_transaction(
    rows: list[asyncpg.Record], 
    update_status: Callable[[asyncpg.Record, tuple[Any, Any]], Awaitable[None]], 
    model: WhisperModel,
) -> None:
    for row in rows:
        session_id = row["session_id"]
        pcm_path = Path(row["filename"])
        start_second = row["time_start"]
        end_second = row["time_end"]
        
        logging.info(f"Processing session {session_id} with PCM path: {pcm_path} from {start_second} to {end_second}")
        
        if not pcm_path.exists():
            logging.error(f"PCM file not found: {pcm_path}")
            await update_status(row, (None, "PCM file not found"))
            continue

        if pcm_path.stat().st_size < 10:
            logging.error(f"PCM file is empty: {pcm_path}")
            await update_status(row, (None, "PCM file is empty"))
            continue

        try:
            pcm_audio = await load_pcm_audio(pcm_path, start_second, end_second)
            segments, _ = model.transcribe(pcm_audio, beam_size=1, vad_filter=False)
            transcript_text = " ".join([seg.text for seg in segments])
            await update_status(row, (transcript_text, None))
            logging.info(f"Transcribed session {session_id}: {transcript_text}")
        except Exception as e:
            logging.error(f"Error processing session {session_id}: {e}")
            await update_status(row, (None, str(e)))

async def producer(db: PostgresManager, model: WhisperModel):
    futures = []
    
    while True:
        futures.append(asyncio.create_task(
            db.lock_and_work_transcription(rows_transaction, model, batch_size=BATCH_SIZE)
        ))
        if len(futures) >= MAX_SPAWN:
            await futures.pop(0)
    
async def main():

    model = WhisperModel(
        "medium.en", 
        device="cuda", 
        compute_type="float16"
    )
    async with asyncpg.create_pool(dsn=PG_DSN) as pool:
        db_manager = PostgresManager(pool)
        
        await producer(db_manager, model)