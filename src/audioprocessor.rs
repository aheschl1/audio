use std::{collections::HashMap, path::PathBuf};

use tokio::{
    fs::{File, OpenOptions},
    io::AsyncWriteExt,
    sync::mpsc::Receiver,
};

use crate::Args;

struct SessionState {
    file: File,
    write_count: u32,
}

impl SessionState {
    async fn new(cache_dir: &PathBuf, session_id: u32) -> Self {
        let file_path = cache_dir.join(format!("{session_id}.pcm"));

        let file = OpenOptions::new()
            .create(true)
            .append(true)
            .open(file_path)
            .await
            .expect("failed to open session file");

        Self {
            file,
            write_count: 0,
        }
    }

    async fn write_chunk(&mut self, data: &[u8]) {
        self.file
            .write_all(data)
            .await
            .expect("failed to write audio data");

        self.write_count += 1;

        if self.write_count >= 100 {
            self.flush().await;
            self.write_count = 0;
        }
    }

    async fn flush(&mut self) {
        let _ = self.file.flush().await;
    }
}


pub async fn consume_audio_data(
    mut receiver: Receiver<(u32, Vec<u8>)>,
    args: Args,
) {
    let cache_path = PathBuf::from(args.cache_dir.clone())
        .canonicalize()
        .unwrap_or_else(|_| PathBuf::from(args.cache_dir));

    let mut sessions: HashMap<u32, SessionState> = HashMap::new();

    while let Some((session_id, audio_data)) = receiver.recv().await {
        let session = if let Some(s) = sessions.get_mut(&session_id) {
            s
        } else {
            let state = SessionState::new(&cache_path, session_id).await;
            sessions.insert(session_id, state);
            sessions.get_mut(&session_id).unwrap()
        };

        session.write_chunk(&audio_data).await;
    }

    // flush everything on shutdown
    for (_, session) in sessions.iter_mut() {
        session.flush().await;
    }
}