fn main() -> Result<(), Box<dyn std::error::Error>> {
    prost_build::compile_protos(&["../proto/file_daemon.proto"], &["../proto"])?;

    // libsepol is cross-compiled by `native/build-libsepol.sh` and linked
    // statically into libfiledaemon.so: Android ships no libsepol, and the
    // daemon must link the pinned version it was built against.
    let manifest = std::path::PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?);
    let target = std::env::var("TARGET")?;
    println!("cargo:rerun-if-env-changed=SEPOL_PREFIX");
    if target.contains("android") {
        let prefix = match std::env::var_os("SEPOL_PREFIX") {
            Some(dir) => std::path::PathBuf::from(dir),
            None => manifest.join("sepolicy").join("prefix").join(&target),
        };
        let archive = prefix.join("lib").join("libfile_container.a");
        if !archive.is_file() {
            return Err(format!(
                "the container policy archive for {target} is missing: {}\n\
                 run `bash native/build-libsepol.sh {}` first",
                archive.display(),
                if target.starts_with("x86_64") {
                    "x86_64"
                } else {
                    "aarch64"
                }
            )
            .into());
        }
        println!("cargo:rustc-link-search=native={}", prefix.join("lib").display());
        println!("cargo:rustc-link-lib=static=file_container");
        println!("cargo:rerun-if-changed={}", archive.display());
    }
    Ok(())
}
