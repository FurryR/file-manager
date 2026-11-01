use std::path::PathBuf;

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=native/egl_shim.c");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        return;
    }

    // libxkbcommon is not part of the NDK. native/build-xkbcommon.sh builds a
    // static archive for the target ABI; Smithay's xkbcommon dependency links
    // against it through the search path below.
    let manifest = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let arch = std::env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_default();
    let (builddir, abi) = match arch.as_str() {
        "aarch64" => ("builddir-aarch64", "arm64-v8a"),
        "x86_64" => ("builddir-x86_64", "x86_64"),
        other => panic!("unsupported android target arch for xkbcommon: {other}"),
    };
    let xkb = manifest.join("third_party/libxkbcommon").join(builddir);
    assert!(
        xkb.join("libxkbcommon.a").is_file(),
        "libxkbcommon.a missing at {} — run `bash native/build-xkbcommon.sh` first",
        xkb.display(),
    );
    println!("cargo:rustc-link-search=native={}", xkb.display());
    println!("cargo:rustc-link-lib=static=xkbcommon");

    // EGL/GLES come from the NDK sysroot.
    println!("cargo:rustc-link-lib=dylib=EGL");
    println!("cargo:rustc-link-lib=dylib=GLESv2");
    println!("cargo:rustc-link-lib=dylib=log");
    println!("cargo:rustc-link-lib=dylib=android");

    // Smithay dlopens `libEGL.so.1`, which does not exist on Android. This
    // shared object claims that soname and links the platform libEGL, so the
    // lookup resolves once the shim is preloaded.
    //
    // Built with the target compiler directly: `cc` only archives static
    // libraries, and the soname is the whole point of this object.
    let out_dir = PathBuf::from(std::env::var("OUT_DIR").unwrap());
    let shim = out_dir.join("libeglshim.so");
    let compiler = cc::Build::new().get_compiler();
    let status = std::process::Command::new(compiler.path())
        .arg("-shared")
        .arg("-fPIC")
        .arg("-O2")
        .arg("-Wl,-soname,libEGL.so.1")
        .arg("-o")
        .arg(&shim)
        .arg(manifest.join("native/egl_shim.c"))
        .arg("-lEGL")
        .status()
        .expect("cannot invoke the Android C compiler for the EGL shim");
    assert!(status.success(), "compiling libeglshim.so failed");

    // Package the shim next to the compositor so `System.loadLibrary("eglshim")`
    // finds it. The app module owns jniLibs.
    let jni = manifest.join("../../app/src/main/jniLibs").join(abi);
    if jni.is_dir() {
        if let Err(error) = std::fs::copy(&shim, jni.join("libeglshim.so")) {
            println!("cargo:warning=cannot stage libeglshim.so: {error}");
        }
    }
}
