fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("cargo:rerun-if-changed=../../proto/hub.proto");
    prost_build::compile_protos(&["../../proto/hub.proto"], &["../../proto"])?;
    Ok(())
}
