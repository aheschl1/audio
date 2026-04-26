use std::{collections::HashMap, path::PathBuf};

use tokio::{
    fs::{File, OpenOptions},
    io::AsyncWriteExt,
    sync::{broadcast, mpsc::Receiver},
};

use crate::{Args, AudioEvent, sql::PostgresManager, vad::VadState};

const VAD_WINDOW: usize = 3200; // bytes (~100ms)

struct SessionState {
    file: File,
    write_count: u32,
    vad_state: VadState,
    path: PathBuf,

    buffer: Vec<u8>,
    pre_speech_buffer: Vec<Vec<u8>>,
}

impl SessionState {
    async fn new(cache_dir: &PathBuf, session_id: i32) -> Self {
        let path_mixin = uuid::Uuid::new_v4();
        let file_path = cache_dir.join(format!("{path_mixin}-{session_id}.pcm"));

        let file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(file_path.clone())
            .await
            .expect("failed to open session file");

        Self {
            file,
            write_count: 0,
            vad_state: VadState::new(),
            pre_speech_buffer: Vec::new(),
            buffer: Vec::new(),
            path: file_path,
        }
    }

    async fn write_chunk(&mut self, data: &[u8]) {
        self.buffer.extend_from_slice(data);

        while self.buffer.len() >= VAD_WINDOW {
            let frame = self.buffer.drain(..VAD_WINDOW).collect::<Vec<_>>();

            let is_speech = self.vad_state.process_frame(&frame);

            if is_speech {
                for b in self.pre_speech_buffer.drain(..) {
                    self.file.write_all(&b).await.unwrap();
                }

                self.file.write_all(&frame).await.unwrap();
            } else {
                self.pre_speech_buffer.push(frame);

                if self.pre_speech_buffer.len() > 10 {
                    self.pre_speech_buffer.remove(0);
                }
            }
        }

        self.write_count += 1;
        if self.write_count >= 100 {
            self.flush().await;
        }
    }

    async fn flush(&mut self) {
        let _ = self.file.flush().await;
        self.write_count = 0;
    }

    async fn close(&mut self) {
        // check if open
        if self.write_count == 0 {
            return;
        }
        let _ = self.file.flush().await;
        let _ = self.file.shutdown().await;
        
    }
}

pub async fn consume_audio_data(
    mut receiver: Receiver<AudioEvent>,
    args: Args,
) {
    let cache_path = PathBuf::from(args.cache_dir.clone())
        .canonicalize()
        .unwrap_or_else(|_| PathBuf::from(args.cache_dir.clone()));

    let database_manager = PostgresManager::new(args.database_url()).await;


    tokio::fs::create_dir_all(&cache_path)
        .await
        .expect("failed to create cache directory");

    let mut sessions: HashMap<i32, SessionState> = HashMap::new();

    while let Some(event) = receiver.recv().await {
        let session_id = match &event {
            AudioEvent::Data(id, _) => *id,
            AudioEvent::Close(id) => *id,
        };
        let session = if let Some(s) = sessions.get_mut(&session_id) {
            s
        } else {
            let state = SessionState::new(&cache_path, session_id).await;
            sessions.insert(session_id, state);
            sessions.get_mut(&session_id).unwrap()
        };

        match event {
            AudioEvent::Data(_, audio_data) => session.write_chunk(&audio_data).await,
            AudioEvent::Close(session_id) => {
                // we mark the status column 1
                database_manager.create_session_chunk(session_id, session.path.clone()).await;
                session.close().await;
                sessions.remove(&session_id);
            },
        }
    }

    // flush everything on shutdown
    for (_, session) in sessions.iter_mut() {
        session.close().await;
    }
}