#!/usr/bin/env python3
"""Extracts the settings page/group layout of the desktop GUI from src/slic3r/GUI/Tab.cpp.

The desktop app arranges options in Tab*::build*() as
    page = add_options_page(L("Quality"), ...);
    optgroup = page->new_optgroup(L("Layer height"), ...);
    optgroup->append_single_option_line("layer_height", ...);   // or get_option("...")
This reproduces that structure as JSON so the Android settings editor shows the same pages and
groups without hand-maintaining a copy:
    {"print": [{"page": "Quality", "groups": [{"group": "Layer height", "options": [...]}]}], ...}
"""
import json
import re
import sys
from pathlib import Path

# Which Tab member functions build which preset type's pages.
FUNCTIONS = {
    "print": ["TabPrint::build"],
    "filament": ["TabFilament::build"],
    "printer": ["TabPrinter::build_fff", "TabPrinter::build_kinematics_page", "TabPrinter::build_unregular_pages"],
}

FUNC_RE = re.compile(r"^\S[^;]*?\b(Tab\w+::\w+)\s*\(", re.M)
PAGE_RE = re.compile(r'add_options_page\(\s*(?:L\("([^"]+)"\))?')
GROUP_RE = re.compile(r'new_optgroup\(\s*L\("([^"]+)"\)')
OPTION_RE = re.compile(r'(?:append_single_option_line|get_option)\(\s*"([a-z0-9_]+)"')


def function_bodies(source: str) -> dict:
    starts = [(m.start(), m.group(1)) for m in FUNC_RE.finditer(source)]
    bodies = {}
    for i, (pos, name) in enumerate(starts):
        end = starts[i + 1][0] if i + 1 < len(starts) else len(source)
        bodies.setdefault(name, "")
        bodies[name] += source[pos:end]
    return bodies


def parse(body: str, pages: list) -> None:
    page = group = None
    for raw in body.splitlines():
        line = raw.split("//", 1)[0]
        if m := PAGE_RE.search(line):
            title = m.group(1) or "Extruder"
            page = next((p for p in pages if p["page"] == title), None)
            if page is None:
                page = {"page": title, "groups": []}
                pages.append(page)
            group = None
        if m := GROUP_RE.search(line):
            if page is None:
                continue
            group = next((g for g in page["groups"] if g["group"] == m.group(1)), None)
            if group is None:
                group = {"group": m.group(1), "options": []}
                page["groups"].append(group)
        for key in OPTION_RE.findall(line):
            if group is not None and key not in group["options"]:
                group["options"].append(key)


def main() -> None:
    tab_cpp, out = Path(sys.argv[1]), Path(sys.argv[2])
    bodies = function_bodies(tab_cpp.read_text(encoding="utf-8"))
    layout = {}
    for preset_type, funcs in FUNCTIONS.items():
        pages: list = []
        for f in funcs:
            parse(bodies.get(f, ""), pages)
        layout[preset_type] = [p for p in pages if any(g["options"] for g in p["groups"])]
    out.write_text(json.dumps(layout, indent=1), encoding="utf-8")
    counts = {t: sum(len(g["options"]) for p in v for g in p["groups"]) for t, v in layout.items()}
    print(f"Settings layout: {counts}")


if __name__ == "__main__":
    main()
