import asyncio
import os
from typing import Any, Awaitable, Callable, TypedDict, Unpack
from uuid import UUID

import asyncpg

POSTGRESS_USER = os.environ.get("POSTGRESS_USER", "app")
POSTGRESS_PASSWORD = os.environ.get("POSTGRESS_PASSWORD", "")

PG_DSN = f"postgresql://{POSTGRESS_USER}:{POSTGRESS_PASSWORD}@127.0.0.1:5432/audio"



class PostgresManager:
    def __init__(self, pool: asyncpg.Pool):
        self._pool = pool
    
    async def lock_and_work_diarization[R, A](
        self, 
        function: Callable[[list[asyncpg.Record], Callable[[asyncpg.Record, tuple[Any, Any]], Awaitable[None]]], Awaitable[R]] | 
                Callable[[list[asyncpg.Record], Callable[[asyncpg.Record, tuple[Any, Any]], Awaitable[None]], A], Awaitable[R]]
        ,
        args: A = None,
        batch_size: int = 10,
    ) -> R:
        async with self._pool.acquire() as connection:
            
            async def update_row_success(record: asyncpg.Record, result: tuple[Any, Any]):
                speakers, embeddings = result
                for speaker_name in speakers:
                    for seg in speakers[speaker_name]:
                        await connection.execute(
                            """
                            INSERT INTO segments (
                                chunk_id, local_speaker_label, 
                                time_start, time_end
                            ) VALUES ($1, $2, $3, $4)
                            """,
                            record.get("id"), seg.local_speaker,
                            seg.start, seg.end
                        )
                await connection.execute(
                    """
                    UPDATE recording_chunks
                    SET status = $1
                    WHERE id = $2
                    """,
                    "done", record.get("id") # type: ignore
                )
                
            async with connection.transaction():
                rows = await connection.fetch(
                    """
                    SELECT id, filename FROM recording_chunks WHERE 
                    status = 'pending'
                    FOR UPDATE SKIP LOCKED
                    LIMIT $1
                    """,
                    batch_size
                )
                # if takes A, pass
                if args is not None:
                    return await function(rows, update_row_success, args) # type: ignore
                return await function(rows, update_row_success) # type: ignore
            
    async def lock_and_work_transcription[R, A](
        self, 
        function: Callable[[list[asyncpg.Record], Callable[[asyncpg.Record, tuple[Any, Any]], Awaitable[None]]], Awaitable[R]] | 
                Callable[[list[asyncpg.Record], Callable[[asyncpg.Record, tuple[Any, Any]], Awaitable[None]], A], Awaitable[R]]
        ,
        args: A = None,
        batch_size: int = 10,
    ) -> R:
        async with self._pool.acquire() as connection:
            
            async def update_row_success(record: asyncpg.Record, result: tuple[Any, Any]):
                ...
                transcript, error = result
                if error is not None:
                    return
                await connection.execute(
                    """
                    UPDATE segments
                    SET transcription = $1, processing_status = 'done'
                    WHERE id = $2
                    """,
                    transcript, record.get("segment_id") # type: ignore
                )
                
            async with connection.transaction():
                rows = await connection.fetch(
                    """
                    SELECT segments.id as segment_id, filename, time_start, time_end, recording_chunks.session_id FROM segments
                    right join recording_chunks on segments.chunk_id = recording_chunks.id
                    where segments.processing_status = 'processing'
                    limit $1
                    ;
                    """,
                    batch_size
                )
                # if takes A, pass
                if args is not None:
                    return await function(rows, update_row_success, args) # type: ignore
                return await function(rows, update_row_success) # type: ignore