import asyncio
from dataclasses import dataclass
import logging
from asyncio import Queue
import os
from typing import Any, Awaitable, Callable

import asyncpg
import numpy as np
from pyannote.audio import Pipeline
import torch
from .common import BATCH_SIZE, MAX_SPAWN, SAMPLE_RATE
from .db import PG_DSN, PostgresManager


@dataclass
class Segment:
    start: float
    end: float
    local_speaker: str

@dataclass
class Embedding:
    embedding: np.ndarray
    local_speaker: str

class Diarisation:
    def __init__(self):
        self.pipeline: Pipeline = Pipeline.from_pretrained(
            "pyannote/speaker-diarization-community-1", 
            token=os.environ.get("HF_TOKEN")
        ) # type: ignore
        assert self.pipeline is not None, "Failed to load diarization pipeline"
    
    @staticmethod
    def load_pcm_audio(file_path: str) -> dict:
        print(f"Loading PCM audio from: {file_path}")
        with open(file_path, "rb") as f:
            pcm_bytes = f.read()
        # interpret as int16
        audio = np.frombuffer(pcm_bytes, dtype=np.int16)
        waveform = torch.from_numpy(audio).float().unsqueeze(0)
        waveform = waveform / 32768.0
        return {
            "waveform": waveform,
            "sample_rate": SAMPLE_RATE
        }
    
    def diarise(self, pcm_audio: dict) -> tuple[dict[str, list[Segment]], dict[str, Embedding]]:
        diarisation_result = self.pipeline(pcm_audio)
        segments = dict()
        embeddings = dict()
        
        for turn, speaker in diarisation_result.exclusive_speaker_diarization:
            if speaker not in segments:
                segments[speaker] = []
            segments[speaker].append(Segment(start=turn.start, end=turn.end, local_speaker=speaker))
            
        embeddings_array = diarisation_result.speaker_embeddings
        for i, speaker in enumerate(diarisation_result.speaker_diarization.labels()):
            embedding = embeddings_array[i]
            embeddings[speaker] = Embedding(embedding=embedding, local_speaker=speaker)
        return segments, embeddings

async def rows_transaction(
    rows: list[asyncpg.Record], 
    update_status: Callable[[asyncpg.Record, tuple[Any, Any]], Awaitable[None]], 
    diariser: Diarisation
) -> None:
    
    raw_audio = Queue()
    results: list[tuple[tuple[dict[str, list[Segment]], dict[str, Embedding]], asyncpg.Record]] = []
    
    
    async def process_row(raw_queue: Queue[tuple[dict, asyncpg.Record]]):
        
        while True:
            pcm_audio, record = await raw_queue.get()
            if pcm_audio is None and record is None:
                raw_queue.task_done()
                break
            diarisation_result = await asyncio.to_thread(diariser.diarise, pcm_audio)
            results.append((diarisation_result, record))
            raw_queue.task_done()
    
    async def load_rows():
        for row in rows:
            pcm_path: str = row.get("filename") # type: ignore
            data = Diarisation.load_pcm_audio(pcm_path)
            await raw_audio.put((data, row))
        raw_audio.put_nowait((None, None)) # sentinel to indicate end of queue
    asyncio.create_task(process_row(raw_audio))
    await load_rows()
    await raw_audio.join()
    
    for result, record in results:
        print("updating status on record", record.get("id"))
        print(result[0])
        await update_status(record, result)
    
    
        
async def producer(db: PostgresManager, diariser: Diarisation):
    futures = []
    
    while True:
        futures.append(asyncio.create_task(
            db.lock_and_work_diarization(rows_transaction, diariser, batch_size=BATCH_SIZE)
        ))
        if len(futures) >= MAX_SPAWN:
            await futures.pop(0)
            
        await asyncio.sleep(5)

async def main():
    diariser = Diarisation()

    async with asyncpg.create_pool(dsn=PG_DSN) as pool:
        db_manager = PostgresManager(pool)
        
        await producer(db_manager, diariser)