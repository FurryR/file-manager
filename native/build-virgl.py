#!/usr/bin/env python3
"""Build the Android ARM64 host VirGL server, including static epoxy/virgl libraries.

All downloads, Python tooling and generated sources live in --work. This does not
install anything globally and never executes target binaries or unit tests.
"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request

SOURCES = {
    "virglrenderer-1.3.0.tar.gz": (
        "https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/virglrenderer-1.3.0/virglrenderer-virglrenderer-1.3.0.tar.gz",
        "56170f8caa1bb642a2624b649e3bcca095ec2834814e5c308efc8a85a709e4ce"),
    "libepoxy-1.5.10.tar.gz": (
        "https://github.com/anholt/libepoxy/archive/refs/tags/1.5.10.tar.gz",
        "a7ced37f4102b745ac86d6a70a9da399cc139ff168ba6b8002b4d8d43c900c15"),
}


def run(args, *, cwd=None, env=None):
    print("+", " ".join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=cwd, env=env, check=True)


def sha256(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def replace(path, before, after, count=1):
    text = path.read_text(encoding="utf-8")
    if text.count(before) != count:
        raise RuntimeError(f"Pinned-source patch no longer matches {path.name}: {before[:70]!r}")
    path.write_text(text.replace(before, after), encoding="utf-8", newline="\n")


def prepare_android(source, epoxy):
    # Use a Meson subproject so static dependency ordering and generated headers
    # are tracked without host pkg-config or a Termux installation.
    replace(source / "meson.build", "epoxy_dep = dependency('epoxy', version: '>= 1.5.4')",
            "epoxy_project = subproject('libepoxy', default_options: ['default_library=static', 'egl=yes', 'glx=no', 'x11=false', 'tests=false', 'docs=false'])\n"
            "epoxy_dep = epoxy_project.get_variable('libepoxy_dep')")
    replace(source / "meson.build", "import('python').find_installation('python3')", "import('python').find_installation()")
    replace(source / "meson.build",
            "if cc.has_header('epoxy/egl.h', dependencies: epoxy_dep) and epoxy_dep.get_variable(pkgconfig: 'epoxy_has_egl') == '1'",
            "if with_host_android or (cc.has_header('epoxy/egl.h', dependencies: epoxy_dep) and epoxy_dep.get_variable(pkgconfig: 'epoxy_has_egl') == '1')")
    replace(epoxy / "meson.build", "gen_dispatch_py = find_program('src/gen_dispatch.py')",
            "gen_dispatch_py = [import('python').find_installation(), files('src/gen_dispatch.py')]")

    # Android's system EGL exposes EGL_DEFAULT_DISPLAY. Do not request Mesa's
    # platform or load ANGLE/Termux libraries. libepoxy already selects the
    # Android libEGL.so / libGLESv2.so names in dispatch_common.c.
    egl = source / "src/vrend/vrend_winsys_egl.c"
    replace(egl, "   EGLConfig egl_conf;", "   EGLConfig egl_conf;\n   EGLSurface egl_surface;")
    replace(egl, "   if (!has_bits(egl->extension_bits, EGL_KHR_SURFACELESS_CONTEXT | EGL_KHR_CREATE_CONTEXT)) {",
            "#ifdef __ANDROID__\n   if (!has_bits(egl->extension_bits, EGL_KHR_CREATE_CONTEXT)) {\n#else\n"
            "   if (!has_bits(egl->extension_bits, EGL_KHR_SURFACELESS_CONTEXT | EGL_KHR_CREATE_CONTEXT)) {\n#endif")
    replace(egl, "#ifdef ENABLE_GBM_ALLOCATION\n   if (virgl_egl_get_display(egl)) {",
            "#ifdef __ANDROID__\n   egl->egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);\n#else\n"
            "#ifdef ENABLE_GBM_ALLOCATION\n   if (virgl_egl_get_display(egl)) {")
    replace(egl, "   if (!egl->egl_display) {\n#ifdef ENABLE_GBM", "#endif /* Android uses the native default display */\n\n   if (!egl->egl_display) {\n#ifdef ENABLE_GBM")
    replace(egl, "   egl->egl_ctx = eglCreateContext(egl->egl_display, egl->egl_conf, EGL_NO_CONTEXT,",
            "#ifdef __ANDROID__\n   if (!has_bit(egl->extension_bits, EGL_KHR_SURFACELESS_CONTEXT)) {\n"
            "      const EGLint pbuffer[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };\n"
            "      egl->egl_surface = eglCreatePbufferSurface(egl->egl_display, egl->egl_conf, pbuffer);\n"
            "      if (egl->egl_surface == EGL_NO_SURFACE) goto fail;\n   }\n#endif\n"
            "   egl->egl_ctx = eglCreateContext(egl->egl_display, egl->egl_conf, EGL_NO_CONTEXT,")
    replace(egl, "   eglMakeCurrent(egl->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE,\n                  egl->egl_ctx);",
            "   if (!eglMakeCurrent(egl->egl_display, egl->egl_surface, egl->egl_surface,\n                  egl->egl_ctx)) goto fail;")
    replace(egl, "   return eglMakeCurrent(egl->egl_display, EGL_NO_SURFACE, EGL_NO_SURFACE,",
            "   return eglMakeCurrent(egl->egl_display, egl->egl_surface, egl->egl_surface,")
    replace(egl, "   eglDestroyContext(egl->egl_display, egl->egl_ctx);",
            "   eglDestroyContext(egl->egl_display, egl->egl_ctx);\n"
            "   if (egl->egl_surface != EGL_NO_SURFACE) eglDestroySurface(egl->egl_display, egl->egl_surface);")

    # API 26+ shared memory works on devices that restrict raw /dev/ashmem or
    # memfd_create. The descriptor is transferred to the guest with SCM_RIGHTS.
    shm = source / "vtest/vtest_shm.c"
    replace(shm, '#include "vtest_shm.h"', '#include "vtest_shm.h"\n#include <android/sharedmem.h>')
    replace(shm, "   free(str);\n   if (fd < 0) {",
            "   if (fd < 0) {\n      fd = ASharedMemory_create(str, size);\n      free(str);\n"
            "      if (fd >= 0) {\n         fcntl(fd, F_SETFD, FD_CLOEXEC);\n         return fd;\n      }\n"
            "      return report_failed_call(\"ASharedMemory_create\", -errno);\n   }\n   free(str);\n   if (fd < 0) {")
    replace(shm, '    int mfd = memfd_create("test", MFD_ALLOW_SEALING);',
            '    int mfd = memfd_create("test", MFD_ALLOW_SEALING);\n    if (mfd < 0) mfd = ASharedMemory_create("vtest-check", 4096);')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", default=os.environ.get("ANDROID_NDK_HOME"), help="Android NDK directory")
    parser.add_argument("--work", type=Path, default=Path(__file__).resolve().parents[2] / "virgl-build")
    parser.add_argument("--tooling", type=Path, help="Existing local pip --target directory containing Meson/Ninja")
    parser.add_argument("--jobs", type=int, default=min(os.cpu_count() or 4, 12))
    args = parser.parse_args()
    if not args.ndk:
        parser.error("set ANDROID_NDK_HOME or pass --ndk")
    ndk = Path(args.ndk).resolve()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    root = Path(__file__).resolve().parent.parent
    host = {"Windows": "windows-x86_64", "Linux": "linux-x86_64", "Darwin": "darwin-x86_64"}.get(platform.system())
    if host is None:
        raise RuntimeError("Unsupported NDK host")
    suffix = ".exe" if platform.system() == "Windows" else ""
    llvm = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    if not (llvm / ("clang" + suffix)).is_file():
        raise RuntimeError(f"NDK clang not found in {llvm}")
    tooling = args.tooling.resolve() if args.tooling else work / "tooling"
    if not all((tooling / module).is_dir() for module in ("mesonbuild", "ninja", "yaml")):
        run([sys.executable, "-m", "pip", "install", "--disable-pip-version-check", "--no-cache-dir", "--no-warn-script-location",
             "--target", tooling, "meson==1.7.2", "ninja==1.11.1.4", "PyYAML==6.0.2"])
    ninja = next((path for path in tooling.rglob("ninja" + suffix) if path.is_file()), None)
    if ninja is None or not ninja.is_file():
        raise RuntimeError("Ninja executable not found in local tooling")
    env = os.environ.copy()
    env["PYTHONPATH"] = str(tooling)
    env["PATH"] = str(ninja.parent) + os.pathsep + str(Path(sys.executable).parent) + os.pathsep + env.get("PATH", "")
    env["NINJA"] = str(ninja)
    env["PKG_CONFIG_LIBDIR"] = str(work / "empty-pkgconfig")
    (work / "empty-pkgconfig").mkdir(exist_ok=True)
    patches = sorted((root / "native/virgl/patches").glob("*.patch"))
    fingerprint = hashlib.sha256(Path(__file__).read_bytes() + b"".join(p.read_bytes() for p in patches)).hexdigest()[:12]
    source_dir = work / ("source-" + fingerprint)
    source = source_dir / "virglrenderer-virglrenderer-1.3.0"
    prepared = source_dir / ".prepared"
    downloads = work / "downloads"
    downloads.mkdir(exist_ok=True)
    if not prepared.exists():
        if source_dir.exists():
            raise RuntimeError(f"Incomplete source preparation at {source_dir}; choose a fresh --work directory")
        source_dir.mkdir()
        for name, (url, checksum) in SOURCES.items():
            archive = downloads / name
            if not archive.exists():
                temporary = archive.with_suffix(".part")
                print("Downloading", url, flush=True)
                urllib.request.urlretrieve(url, temporary)
                if sha256(temporary) != checksum:
                    raise RuntimeError(f"SHA256 mismatch for {name}")
                temporary.replace(archive)
            if sha256(archive) != checksum:
                raise RuntimeError(f"SHA256 mismatch for {archive}")
            with tarfile.open(archive) as tar:
                tar.extractall(source_dir, filter="data")
        for patch in patches:
            run(["git", "apply", "--check", patch], cwd=source)
            run(["git", "apply", patch], cwd=source)
        epoxy = source / "subprojects/libepoxy"
        shutil.copytree(source_dir / "libepoxy-1.5.10", epoxy)
        prepare_android(source, epoxy)
        prepared.write_text(fingerprint, encoding="ascii")
    cross = work / "android-aarch64.ini"
    def quote(path):
        return "'" + str(path).replace("\\", "/").replace("'", "\\'") + "'"
    cross.write_text("\n".join([
        "[binaries]",
        f"c = [{quote(llvm / ('clang' + suffix))}, '--target=aarch64-linux-android26', '--sysroot={str(llvm.parent / 'sysroot').replace(chr(92), '/')}']",
        f"ar = {quote(llvm / ('llvm-ar' + suffix))}", f"strip = {quote(llvm / ('llvm-strip' + suffix))}",
        "[host_machine]", "system = 'android'", "cpu_family = 'aarch64'", "cpu = 'aarch64'", "endian = 'little'",
        "[properties]", "needs_exe_wrapper = true", "[built-in options]",
        "c_args = ['-fPIC', '-Wno-gnu-statement-expression-from-macro-expansion']",
        "c_link_args = ['-landroid', '-llog', '-Wl,-z,max-page-size=16384']", "",
    ]), encoding="utf-8")
    build = work / ("build-" + fingerprint)
    meson = [sys.executable, "-m", "mesonbuild.mesonmain"]
    if not (build / "build.ninja").exists():
        run(meson + ["setup", build, source, "--cross-file", cross, "--buildtype=release", "--wrap-mode=nofallback",
                     "-Ddefault_library=static", "-Dplatforms=egl", "-Dvenus=false", "-Dtests=false", "-Dfuzzer=false",
                     "-Dvideo=false", "-Ddrm-renderers=[]", "-Dtracing=none"], env=env)
    run([ninja, "-C", build, "-j", str(args.jobs), "vtest/virgl_test_server"], env=env)
    binary = build / "vtest/virgl_test_server"
    run([llvm / ("llvm-strip" + suffix), "--strip-unneeded", binary])
    info = subprocess.check_output([str(llvm / ("llvm-readelf" + suffix)), "-h", "-d", str(binary)], text=True)
    if "AArch64" not in info:
        raise RuntimeError("Renderer is not an AArch64 ELF")
    needed = re.findall(r"Shared library: \[(.*?)\]", info)
    allowed = {"libc.so", "libm.so", "libdl.so", "libandroid.so", "liblog.so"}
    if set(needed) - allowed:
        raise RuntimeError(f"Unexpected non-system runtime dependencies: {needed}")
    output = root / "app/src/main/jniLibs/arm64-v8a/libvirglserver.so"
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(binary, output)
    notices = root / "app/src/main/assets/native-licenses"
    notices.mkdir(parents=True, exist_ok=True)
    for license_file in (root / "native/virgl/licenses").iterdir():
        if license_file.is_file():
            shutil.copy2(license_file, notices / license_file.name)
    print("Staged", output, "SHA256", sha256(output), flush=True)
    print("System libraries:", ", ".join(needed), flush=True)


if __name__ == "__main__":
    main()
