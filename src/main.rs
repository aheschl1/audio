mod audioprocessor;

use tokio::sync::mpsc::Sender;
use tonic::Streaming;
use tonic::{transport::Server, Request, Response, Status};

use audioprocessor::consume_audio_data;

pub mod audio {
    tonic::include_proto!("audio");
}

use audio::audio_service_server::{AudioService, AudioServiceServer};
use audio::{UploadAudioRequest, UploadAudioResponse};

#[derive(Clone)]
struct Context {
    audio_sender: Sender<(u32, Vec<u8>)>,
}

#[derive(Clone)]
struct AudioServiceImpl {
    context: Context,
}

#[tonic::async_trait]
impl AudioService for AudioServiceImpl {
    async fn upload_audio_stream(
        &self,
        request: Request<Streaming<UploadAudioRequest>>,
    ) -> Result<Response<UploadAudioResponse>, Status> {
        let mut stream = request.into_inner();

        while let Some(chunk) = stream.message().await? {
            if chunk.audio_data.is_empty() {
                continue;
            }

            self.context
                .audio_sender
                .send((chunk.session_id, chunk.audio_data))
                .await
                .map_err(|_| Status::internal("queue error"))?;
        }

        Ok(Response::new(UploadAudioResponse {
            status: "ok".to_string(),
        }))
    }
}

#[derive(clap::Parser)] 
pub struct Args { 
    #[arg(short, long, default_value="./.cache")] 
    pub cache_dir: String
}

#[tokio::main]
async fn main() {
    let (sender, receiver) = tokio::sync::mpsc::channel(100);
    let args: Args = clap::Parser::parse();

    let context = Context {
        audio_sender: sender,
    };


    let service = AudioServiceImpl { context };

    tokio::spawn(consume_audio_data(receiver, args));

    Server::builder()
        .add_service(AudioServiceServer::new(service))
        .serve("10.8.0.1:4392".parse().unwrap())
        .await
        .unwrap();
}