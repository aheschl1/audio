use std::path::PathBuf;
use std::str::FromStr;

use sqlx::postgres::{PgConnectOptions, PgPoolOptions};
use sqlx::PgPool;

#[derive(Clone)]
pub struct PostgresManager {
    pool: PgPool,
}

impl PostgresManager {
    pub async fn new(sql_address: String) -> Self {
        
        let options = PgConnectOptions::from_str(&sql_address)
            .expect("invalid postgres url");

        let pool = PgPoolOptions::new()
            .max_connections(10)
            .connect_with(options)
            .await
            .expect("failed to connect to database");
    
        Self {
            pool: pool,
        }
    }


    pub async fn start_session(&self, user_id: i32) -> i32 {
        let result = sqlx::query(
            "INSERT INTO sessions (user_id) VALUES ($1) RETURNING id"
        )
        .bind(user_id)
        .fetch_one(&self.pool)
        .await
        .expect("failed to insert session");

        sqlx::Row::get::<i32, _>(&result, "id")
    }

    pub async fn create_session_chunk(
        &self,
        session_id: i32,
        filepath: PathBuf,
    ) -> i32 {
        let result = sqlx::query(
            "INSERT INTO recording_chunks (session_id, filename) VALUES ($1, $2) RETURNING id"
        )
        .bind(session_id)
        .bind(filepath.to_string_lossy().into_owned())
        .fetch_one(&self.pool)
        .await
        .expect("failed to insert session chunk");

        sqlx::Row::get::<i32, _>(&result, "id")
    }
}