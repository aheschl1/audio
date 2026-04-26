import logging

import click
import asyncio

@click.command()
@click.option('--target', help='The target to run', required=True)
def main(target):
    # tell asyncio to use threads for subprocesses
    # asyncio.get_running_loop().set_default_executor(asyncio.ThreadPoolExecutor())
    if target == 'transcriber':
        from workers.transcriber import main as transcriber_main
        asyncio.run(transcriber_main())
    elif target == 'diariser':
        from workers.diariser import main as diariser_main
        asyncio.run(diariser_main())

if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO, 
        format='%(asctime)s - %(levelname)s - %(message)s'
    )
    main() # type: ignore