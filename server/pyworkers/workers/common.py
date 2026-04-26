import asyncio
import logging
from multiprocessing import pool
import os
from typing import Any, Awaitable, Callable

import asyncpg
from .db import PostgresManager


BATCH_SIZE = 2
MAX_SPAWN = 1
SAMPLE_RATE = 16000



class ProducerConsumer:
    def __init__(
        self, 
        consumer: Callable[[asyncio.Queue, PostgresManager], Awaitable[None]],
        table: str,
        status_column: str,
        required_value: Any,
    ):
        self.consumer = consumer
        self.tasks = asyncio.Queue()
        self.table = table
        self.status_column = status_column
        self.required_value = required_value        


    async def producer(self, tasks: asyncio.Queue, db: PostgresManager):            
        while True:
            try:
                rows = await db.query_rows_with_lock(
                    table=self.table,
                    status_column=self.status_column,
                    required_value=self.required_value
                )
                for row in rows:
                    logging.info(f"sending session {row['id']} for processing")
                    await tasks.put((row['id'], row['pcm_root']))
                        
            except Exception as e:
                logging.error(f"Database error in watch loop: {e}")

            await asyncio.sleep(1)
    
    async def run(self):
        async with asyncpg.create_pool(PG_DSN, min_size=2, max_size=10) as pool:
            manager = PostgresManager(pool)
            asyncio.gather(
                self.consumer(self.tasks, manager),
                self.producer(self.tasks, manager)
            )
        