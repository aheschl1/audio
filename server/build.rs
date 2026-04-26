fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("cargo:rerun-if-changed=../proto/audio.proto");
    tonic_prost_build::compile_protos("../proto/audio.proto").unwrap();
    Ok(())
}