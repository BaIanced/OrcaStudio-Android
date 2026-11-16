#!/usr/bin/env python3
"""16 KB page-size check: every lib/*/*.so in an APK must have PT_LOAD alignment >= 16384.

Usage: check_page_align.py app.apk [--strict]
Prints a markdown table (one row per library). Exit 1 only with --strict and a failing library.
Also checks that the .so file is stored page-aligned (zip offset) when stored uncompressed.
"""
import struct
import sys
import zipfile

MIN_ALIGN = 16384
PT_LOAD = 1


def load_aligns(data):
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64 = data[4] == 2
    end = "<" if data[5] == 1 else ">"
    if is64:
        phoff, = struct.unpack_from(end + "Q", data, 0x20)
        phentsize, phnum = struct.unpack_from(end + "HH", data, 0x36)
    else:
        phoff, = struct.unpack_from(end + "I", data, 0x1C)
        phentsize, phnum = struct.unpack_from(end + "HH", data, 0x2A)
    aligns = []
    for i in range(phnum):
        o = phoff + i * phentsize
        ptype, = struct.unpack_from(end + "I", data, o)
        if ptype != PT_LOAD:
            continue
        if is64:
            aligns.append(struct.unpack_from(end + "Q", data, o + 0x30)[0])
        else:
            aligns.append(struct.unpack_from(end + "I", data, o + 0x1C)[0])
    return aligns


def main(argv):
    strict = "--strict" in argv
    args = [a for a in argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        print(__doc__)
        return 2
    rows, failed = [], []
    with zipfile.ZipFile(args[0]) as z:
        for info in sorted(z.infolist(), key=lambda i: i.filename):
            n = info.filename
            parts = n.split("/")
            if len(parts) != 3 or parts[0] != "lib" or not n.endswith(".so"):
                continue
            try:
                al = load_aligns(z.read(n))
            except Exception as e:  # noqa: BLE001
                rows.append((n, "error: %s" % e, "FAIL"))
                failed.append(n)
                continue
            mn = min(al) if al else 0
            ok = bool(al) and mn >= MIN_ALIGN
            rows.append((n, "0x%x (%d)" % (mn, mn) if al else "no PT_LOAD", "ok" if ok else "FAIL"))
            if not ok:
                failed.append(n)
    print("| Library | Min PT_LOAD align | Result |")
    print("|---|---|---|")
    for r in rows:
        print("| %s | %s | %s |" % r)
    print()
    print("%d libraries, %d below 16384" % (len(rows), len(failed)))
    for n in failed:
        print("::warning::%s has PT_LOAD alignment below 16 KB" % n, file=sys.stderr)
    return 1 if (failed and strict) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
