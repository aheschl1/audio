const ENTER_THRESHOLD: f32 = 0.01;
const EXIT_THRESHOLD: f32 = 0.001;

pub struct VadState {
    in_speech: bool,
    silence_count: u32,
    speech_count: u32,
}

impl VadState {
    pub fn new() -> Self {
        Self {
            in_speech: false,
            silence_count: 0,
            speech_count: 0,
        }
    }

    pub fn process_frame(&mut self, data: &[u8]) -> bool {
        // placeholder scoring function for now
        let score = self.estimate_speech_score(data);

        let is_speech = score > ENTER_THRESHOLD;
        let is_hard_silence = score < EXIT_THRESHOLD;

        if self.in_speech {
            // Exit speech on sustained non-speech, not only hard silence.
            // This prevents staying "in speech" forever in the middle band.
            if !is_speech {
                self.silence_count += 1;
                self.speech_count = 0;
            } else {
                self.silence_count = 0;
            }

            // Extra weight for very low-energy frames to exit faster.
            if is_hard_silence {
                self.silence_count += 1;
            }

            if self.silence_count > 3 {
                self.in_speech = false;
            }
        } else {
            if is_speech {
                self.speech_count += 1;
                self.silence_count = 0;
            } else {
                self.speech_count = 0;
            }

            if self.speech_count > 2 {
                self.in_speech = true;
            }
        }

        self.in_speech
    }

    fn estimate_speech_score(&self, data: &[u8]) -> f32 {
        if data.len() < 2 {
            return 0.0;
        }

        let samples = data
            .chunks_exact(2)
            .map(|b| i16::from_le_bytes([b[0], b[1]]))
            .collect::<Vec<_>>();

        let energy: f32 = samples
            .iter()
            .map(|s| (*s as f32).abs())
            .sum();

        let norm = energy / (samples.len() as f32 * i16::MAX as f32);

        let norm = norm.clamp(0.0, 1.0);
        
        norm

    }
}