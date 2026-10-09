#!/usr/bin/env python3
"""Records a new OrcaSlicer commit in android/UPSTREAM.md and lists what to review.

Used by .github/workflows/orca-upstream-watch.yml after it moves the src-orca submodule. The
src-orca checkout must be at the new commit (version.inc is read from it).

usage: bump_upstream.py <UPSTREAM.md> <src-orca dir> <commit> <commit date YYYY-MM-DD> [changed-files.txt]

With changed-files.txt (paths changed between the old and the new commit, one per line), prints
the ones the port depends on (the list under "Updating to a newer upstream" in UPSTREAM.md).
"""
import re
import sys
from pathlib import Path

# Paths the Android port copies, extracts or mirrors; see UPSTREAM.md. Upstream dependency
# recipes count only when android/deps/CMakeLists.txt includes them (see used_deps()).
REVIEW = re.compile(
    r"^(deps/CMakeLists\.txt|CMakeLists\.txt|cmake/modules/.*"
    r"|src/libslic3r/CMakeLists\.txt|src/OrcaSlicer\.cpp"
    r"|src/slic3r/GUI/(Tab|ConfigManipulation|Plater|GUI_App|DeviceErrorDialog|HMS|DeviceManager)\.cpp"
    r"|src/slic3r/GUI/DeviceCore/(DevHMS|DevFilaSystem)\.cpp"
    r"|src/slic3r/Utils/ColorSpaceConvert\.cpp"
    r"|src/slic3r/GUI/Jobs/ArrangeJob\.cpp|src/slic3r/GUI/Gizmos/GLGizmoCut\.cpp)$"
)


def used_deps(android: Path) -> set[str]:
    """Names of the upstream recipes the Android deps superbuild includes."""
    deps = (android / "deps" / "CMakeLists.txt").read_text()
    return set(re.findall(r"include\(\$\{UPSTREAM_DEPS\}/([^/]+)/", deps))


def version(src: Path) -> tuple[str, str]:
    inc = (src / "version.inc").read_text()
    orca = re.search(r'set\(SoftFever_VERSION "([^"]+)"\)', inc)
    slic3r = re.search(r'set\(SLIC3R_VERSION "([^"]+)"\)', inc)
    if not orca or not slic3r:
        sys.exit("bump_upstream: cannot read SoftFever_VERSION / SLIC3R_VERSION from version.inc")
    return orca.group(1), slic3r.group(1)


def main() -> None:
    if len(sys.argv) not in (5, 6):
        sys.exit(__doc__)
    upstream, src, commit, date = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3], sys.argv[4]
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
        sys.exit(f"bump_upstream: bad commit or date: {commit} {date}")
    orca, slic3r = version(src)

    text = upstream.read_text()
    rows = {
        r"^\| Commit \|.*$": f"| Commit | `{commit}` |",
        r"^\| Commit date \|.*$": f"| Commit date | {date} |",
        r"^\| Version \|.*$": f"| Version | OrcaSlicer {orca} (`SLIC3R_VERSION` {slic3r}) |",
    }
    for pattern, row in rows.items():
        text, n = re.subn(pattern, row, text, count=1, flags=re.M)
        if n != 1:
            sys.exit(f"bump_upstream: row not found in {upstream}: {pattern}")
    upstream.write_text(text)
    print(f"OrcaSlicer {orca} ({slic3r}) at {commit[:12]}, {date}")

    if len(sys.argv) == 6:
        changed = Path(sys.argv[5]).read_text().split()
        deps = used_deps(Path(__file__).resolve().parents[1])
        review = [p for p in changed
                  if REVIEW.match(p) or (p.startswith("deps/") and p.split("/")[1] in deps)]
        for path in sorted(review):
            print(f"review: {path}")


if __name__ == "__main__":
    main()
