#!/usr/bin/env python3
"""APK size report with budgets (docs/PERFORMANCE.md).

Groups the entries of an APK, prints a markdown table (compressed = bytes in the APK, raw =
uncompressed) and checks every group against android/size-budget.json. Over budget prints a
GitHub `::warning::` line; `--strict` turns that into exit code 1.

    python android/scripts/size_report.py app-release.apk [--budget android/size-budget.json]
        [--json out.json] [--baseline previous.json] [--strict]

`--json` writes the group totals; feed an older one back with `--baseline` to get a delta column.
"""
import argparse
import fnmatch
import json
import os
import sys
import zipfile

# First matching pattern wins; order matters.
GROUPS = [
    ("native/orca_jni", "lib/*/liborca_jni.so"),
    ("native/openscad", "lib/*/libopenscad.so"),
    ("native/python", "lib/*/*python*.so"),
    ("native/python", "lib/*/*chaquopy*.so"),
    ("native/other", "lib/*"),
    ("dex", "classes*.dex"),
    ("assets/vendors", "assets/vendors/*"),
    ("assets/i18n", "assets/i18n/*"),
    ("assets/python", "assets/python/*"),
    ("assets/resources", "assets/resources.zip"),
    ("assets/other", "assets/*"),
    ("android-res", "resources.arsc"),
    ("android-res", "res/*"),
    ("other", "*"),
]

MB = 1024 * 1024


def group_of(name):
    for group, pattern in GROUPS:
        if fnmatch.fnmatch(name, pattern):
            return group
    return "other"


def measure(apk):
    groups = {}
    with zipfile.ZipFile(apk) as z:
        for info in z.infolist():
            g = groups.setdefault(group_of(info.filename), {"compressed": 0, "raw": 0, "files": 0})
            g["compressed"] += info.compress_size
            g["raw"] += info.file_size
            g["files"] += 1
    groups["total"] = {"compressed": os.path.getsize(apk), "raw": sum(g["raw"] for g in groups.values()),
                       "files": sum(g["files"] for g in groups.values())}
    return groups


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--budget", default=os.path.join(here, "..", "size-budget.json"))
    ap.add_argument("--json")
    ap.add_argument("--baseline")
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()

    groups = measure(args.apk)
    with open(args.budget) as f:
        budget = {k: v for k, v in json.load(f).items() if not k.startswith("_")}
    base = {}
    if args.baseline and os.path.exists(args.baseline):
        with open(args.baseline) as f:
            base = json.load(f)

    over = []
    print("| Group | In APK (MB) | Raw (MB) | Files | Budget (MB) | Delta (KB) |")
    print("|---|---:|---:|---:|---:|---:|")
    for name in sorted(groups, key=lambda k: (k == "total", -groups[k]["compressed"])):
        g = groups[name]
        limit = budget.get(name)
        delta = ""
        if name in base:
            delta = "%+d" % round((g["compressed"] - base[name]["compressed"]) / 1024)
        mark = ""
        if limit is not None and g["compressed"] > limit * MB:
            mark = " **OVER**"
            over.append((name, g["compressed"] / MB, limit))
        print("| %s | %.2f%s | %.2f | %d | %s | %s |" % (
            name, g["compressed"] / MB, mark, g["raw"] / MB, g["files"],
            "%.1f" % limit if limit is not None else "-", delta))

    for name, size, limit in over:
        print("::warning::APK size budget: %s is %.2f MB, budget %.1f MB (android/size-budget.json)"
              % (name, size, limit), file=sys.stderr)
    if args.json:
        with open(args.json, "w") as f:
            json.dump(groups, f, indent=1, sort_keys=True)
    return 1 if over and args.strict else 0


if __name__ == "__main__":
    sys.exit(main())
