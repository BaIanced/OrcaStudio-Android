#!/usr/bin/env python3
"""Stages CPython for Android for the plugin runtime.

    stage_python.py <build root>

Uses Chaquopy's CPython builds from Maven Central (MIT; python.org's own Android releases start
at 3.14, while OrcaSlicer's plugin host is written for 3.12 and checks for lib/python3.12).
Downloads the pinned release once (checksums verified) and produces, under <build root>:
  python/prefix/include/             headers for the native core build
  python/prefix/lib/                 libpython3.12.so to link against
  jniLibs/arm64-v8a/                 libpython3.12.so and the libraries it and its extension
                                     modules link (OpenSSL, SQLite), as app native libraries
  python-assets/python/stdlib.zip    lib/python3.12/ (the standard library and lib-dynload),
                                     which the app extracts to <data dir>/python, the home
                                     OrcaSlicer's PythonInterpreter looks for
  python-assets/python/version       the release, so the app re-extracts after an update
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
    if os.path.exists(stamp) and open(stamp).read() == VERSION:
        return
    native = zipfile.ZipFile(fetch(root, "arm64-v8a.zip"))
    stdlib = zipfile.ZipFile(fetch(root, "stdlib.zip"))

    prefix = os.path.join(root, "python", "prefix")
    shutil.rmtree(os.path.join(root, "python"), ignore_errors=True)
    jnilibs = os.path.join(root, "jniLibs", "arm64-v8a")
    os.makedirs(os.path.join(prefix, "lib"), exist_ok=True)
    os.makedirs(jnilibs, exist_ok=True)
    for info in native.infolist():
        if info.is_dir():
            continue
        if info.filename.startswith("include/"):
            native.extract(info, prefix)
        elif info.filename.startswith("jniLibs/arm64-v8a/"):
            name = os.path.basename(info.filename)
            with native.open(info) as src, open(os.path.join(jnilibs, name), "wb") as dst:
                shutil.copyfileobj(src, dst)
            if name == f"libpython{SHORT}.so":
                shutil.copy2(os.path.join(jnilibs, name), os.path.join(prefix, "lib", name))

    assets = os.path.join(root, "python-assets", "python")
    shutil.rmtree(assets, ignore_errors=True)
    os.makedirs(assets)
    lib = f"lib/python{SHORT}/"
    with zipfile.ZipFile(os.path.join(assets, "stdlib.zip"), "w", zipfile.ZIP_DEFLATED) as out:
        for info in stdlib.infolist():
            if not info.is_dir():
                out.writestr(lib + info.filename, stdlib.read(info))
        for info in native.infolist():
            name = os.path.basename(info.filename)
            if info.filename.startswith("lib-dynload/arm64-v8a/") and name and not name.startswith(SKIP_PREFIXES):
                out.writestr(lib + "lib-dynload/" + name, native.read(info))
    with open(os.path.join(assets, "version"), "w") as f:
        f.write(VERSION)
    with open(stamp, "w") as f:
        f.write(VERSION)


if __name__ == "__main__":
    main()
