fn main() -> Result<(), Box<dyn std::error::Error>> {
    // run0 only speaks the hub's own protocol; the daemon's file protocol is
    // never exposed to it.
    prost_build::compile_protos(&["../proto/hub.proto"], &["../proto"])?;
    Ok(())
}
