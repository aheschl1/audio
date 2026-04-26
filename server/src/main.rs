mod audioprocessor;
mod sql;
mod vad;
mod common;

use std::path::PathBuf;

use tokio::sync::{broadcast, mpsc::Sender};
use tonic::Streaming;
use tonic::{transport::Server, Request, Response, Status};

use audioprocessor::consume_audio_data;

const DEBUG_USER: i32 = 1;

pub mod audio {
    tonic::include_proto!("audio");
}

use audio::audio_service_server::{AudioService, AudioServiceServer};
use audio::{UploadAudioRequest, UploadAudioResponse};

use crate::sql::PostgresManager;

const MAX_CHUNKS_PER_SESSION: u32 = 100;

#[derive(Clone)]
struct Context {
    audio_sender: Sender<AudioEvent>,
    database: PostgresManager,
    shutdown_tx: broadcast::Sender<()>,
}

#[derive(Clone)]
struct AudioServiceImpl {
    context: Context,
}

pub enum AudioEvent {
    Data(i32, Vec<u8>),
    Close(i32),
}

#[tonic::async_trait]
impl AudioService for AudioServiceImpl {
    async fn upload_audio_stream(
        &self,
        request: Request<Streaming<UploadAudioRequest>>,
    ) -> Result<Response<UploadAudioResponse>, Status> {
        println!("Received upload_audio_stream request from {:?}", request.remote_addr());
        let mut stream = request.into_inner();
        let mut nchunks = 0;
        let mut stream_error: Option<Status> = None;
        let mut shutdown_rx = self.context.shutdown_tx.subscribe();
        
        let current_id = self.context.database.start_session(DEBUG_USER).await;
        println!("Started session with id: {}", current_id);

        loop {
            tokio::select! {
                _ = shutdown_rx.recv() => {
                    println!("upload_audio_stream: shutting down");
                    break;
                }
                msg = stream.message() => match msg {
                Ok(Some(chunk)) => {
                    if chunk.audio_data.is_empty() {
                        continue;
                    }

                    self.context
                        .audio_sender
                        .send(AudioEvent::Data(current_id, chunk.audio_data))
                        .await
                        .map_err(|_| Status::internal("queue error"))?;

                    nchunks += 1;

                    if nchunks >= MAX_CHUNKS_PER_SESSION {
                        println!("upload_audio_stream: reached max chunks for session {}, closing session", current_id);
                        self.context
                            .audio_sender
                            .send(AudioEvent::Close(current_id))
                            .await
                            .map_err(|_| Status::internal("queue error"))?;
                        nchunks = 0;
                    }
                }
                Ok(None) => {
                    // Client finished stream normally.
                    break;
                }
                Err(e) => {
                    println!("upload_audio_stream ended with client/transport error: {e}");
                    stream_error = Some(e);
                    break;
                }
                },
            }
        }

        // Best effort: ensure any open chunk is closed even on stream errors.
        if nchunks > 0 {
            self.context
                .audio_sender
                .send(AudioEvent::Close(current_id))
                .await
                .map_err(|_| Status::internal("queue error"))?;
        }

        if let Some(e) = stream_error {
            return Err(e);
        }

        Ok(Response::new(UploadAudioResponse {
            status: "ok".to_string(),
        }))
    }
}

#[derive(clap::Parser)] 
pub struct Args { 
    #[arg(short, long, default_value="./.cache")] 
    pub cache_dir: String,
    #[arg(short, long, default_value="audio")]
    database_name: String
}

impl Args {
    pub fn cache_dir(&self) -> String {
        let path = PathBuf::from(self.cache_dir.clone());
        std::fs::create_dir_all(&path).expect("failed to create cache directory");
        path.canonicalize()
            .unwrap_or(path)
            .to_string_lossy()
            .into_owned()
    }

    pub fn database_url(&self) -> String {
        // postgress
        let db_user = std::env::var("POSTGRESS_USER").unwrap_or_else(|_| "user".to_string());
        let db_password = std::env::var("POSTGRESS_PASSWORD").unwrap_or_else(|_| "password".to_string());
        format!("postgres://{}:{}@localhost/{}", db_user, db_password, self.database_name)
    }
}

#[tokio::main]
async fn main() {
    let (sender, receiver) = tokio::sync::mpsc::channel(100);
    let (shutdown_tx, _) = broadcast::channel(1);
    let args: Args = clap::Parser::parse();
    let database = PostgresManager::new(args.database_url()).await;

    let context = Context {
        audio_sender: sender,
        database: database,
        shutdown_tx: shutdown_tx.clone(),
    };


    let service = AudioServiceImpl { context };

    tokio::spawn(consume_audio_data(receiver, args));

    let shutdown_future = async move {
        if let Err(e) = tokio::signal::ctrl_c().await {
            eprintln!("failed to listen for Ctrl+C: {e}");
        }
        let _ = shutdown_tx.send(());
    };

    Server::builder()
        .add_service(AudioServiceServer::new(service))
        .serve_with_shutdown("10.8.0.1:4392".parse().unwrap(), shutdown_future)
        .await
        .unwrap();
}