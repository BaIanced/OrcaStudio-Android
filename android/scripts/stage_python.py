#!/usr/bin/env python3
"""Stages CPython for Android for the plugin runtime.

    stage_python.py <build root>

Uses Chaquopy's CPython builds from Maven Central (MIT; python.org's own Android releases start
at 3.14, while OrcaSlicer's plugin host is written for 3.12 and checks for lib/python3.12).
Downloads the pinned release once (checksums verified) and produces, under <build root>:
  python/prefix/include/             headers for the native core build
  python/prefix/lib/                 libpython3.12.so to link against
  jniLibs/arm64-v8a/                 libpython3.12.so, as an app native library
  python-assets/python/runtime       where the app fetches the rest from (these same pinned
                                     Maven Central files) and what goes where under
                                     <data dir>/python, the home OrcaSlicer's PythonInterpreter
                                     looks for: the standard library, lib-dynload and the
                                     libraries its modules link (OpenSSL, SQLite). Fetched on the
                                     first plugin start, so they cost no APK size.
"""
import hashlib
import os
import shutil
import sys
import urllib.request
import zipfile

VERSION = "3.12.12-0"
SHORT = "3.12"
BASE = f"https://repo1.maven.org/maven2/com/chaquo/python/target/{VERSION}/target-{VERSION}"
FILES = {
    "arm64-v8a.zip": "7a4a278ec1fed0e0d0359fbec71d6d481d79a7efbc8e2090267a91046417027a",
    "stdlib.zip": "cfeb0137cb3ab149b0817d9ec15091e6e667db7151e4f79ad46b58b02a68f28c",
}
LIBPYTHON = f"libpython{SHORT}.so"
# Libraries the extension modules link, loaded by the app in this order before Python starts.
RUNTIME_LIBS = ("libcrypto_python.so", "libssl_python.so", "libsqlite3_python.so")
# Bump when the staged layout changes, so a cached build root is staged again.
STAMP = VERSION + " on-demand"
# CPython's own test extension modules.
SKIP_PREFIXES = ("_test", "_xxtestfuzz", "xxlimited", "xxsubtype", "_ctypes_test")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(root, suffix):
    path = os.path.join(root, "downloads", f"python-{VERSION}-{suffix}")
    if os.path.exists(path) and sha256(path) == FILES[suffix]:
        return path
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with urllib.request.urlopen(f"{BASE}-{suffix}", timeout=120) as r, open(path + ".part", "wb") as f:
        shutil.copyfileobj(r, f)
    digest = sha256(path + ".part")
    if digest != FILES[suffix]:
        os.remove(path + ".part")
        sys.exit(f"{BASE}-{suffix}: checksum {digest} != {FILES[suffix]}")
    os.replace(path + ".part", path)
    return path


def main():
    root = os.path.abspath(sys.argv[1])
    stamp = os.path.join(root, "python", "staged")
    if os.path.exists(stamp) and open(stamp).read() == STAMP:
        return
    native = zipfile.ZipFile(fetch(root, "arm64-v8a.zip"))
    fetch(root, "stdlib.zip")

    prefix = os.path.join(root, "python", "prefix")
    shutil.rmtree(os.path.join(root, "python"), ignore_errors=True)
    jnilibs = os.path.join(root, "jniLibs", "arm64-v8a")
    os.makedirs(os.path.join(prefix, "lib"), exist_ok=True)
    os.makedirs(jnilibs, exist_ok=True)
    # Earlier layouts put all of the release's libraries here.
    for name in os.listdir(jnilibs):
        if ("python" in name or "chaquopy" in name) and name != LIBPYTHON:
            os.remove(os.path.join(jnilibs, name))
    for info in native.infolist():
        if info.is_dir():
            continue
        if info.filename.startswith("include/"):
            native.extract(info, prefix)
        elif info.filename == f"jniLibs/arm64-v8a/{LIBPYTHON}":
            with native.open(info) as src, open(os.path.join(jnilibs, LIBPYTHON), "wb") as dst:
                shutil.copyfileobj(src, dst)
            shutil.copy2(os.path.join(jnilibs, LIBPYTHON), os.path.join(prefix, "lib", LIBPYTHON))

    # The rest of the runtime is fetched by the app (Plugins.preparePythonHome) on the first plugin
    # start; this manifest says from where, and what goes where under <data dir>/python.
    lib = f"lib/python{SHORT}/"
    manifest = [f"version {VERSION}"]
    for suffix in FILES:
        manifest.append(f"fetch {suffix} {BASE}-{suffix} {FILES[suffix]}")
    manifest.append(f"dir stdlib.zip  {lib}")
    for info in native.infolist():
        name = os.path.basename(info.filename)
        if info.filename.startswith("lib-dynload/arm64-v8a/") and name and not name.startswith(SKIP_PREFIXES):
            manifest.append(f"file arm64-v8a.zip {info.filename} {lib}lib-dynload/{name}")
    for name in RUNTIME_LIBS:
        manifest.append(f"file arm64-v8a.zip jniLibs/arm64-v8a/{name} lib/{name}")
    for name in RUNTIME_LIBS:
        manifest.append(f"load lib/{name}")
    assets = os.path.join(root, "python-assets", "python")
    shutil.rmtree(assets, ignore_errors=True)
    os.makedirs(assets)
    with open(os.path.join(assets, "runtime"), "w", newline="\n") as f:
        f.write("\n".join(manifest) + "\n")
    with open(stamp, "w") as f:
        f.write(STAMP)


if __name__ == "__main__":
    main()
