#!/usr/bin/env python3
"""Packs the OrcaSlicer resources the Android app needs into its assets directory.

Output (under <assets>/):
  resources.zip         runtime resources libslic3r reads from resources_dir()
  vendors/<Vendor>.zip  one archive per printer vendor: <Vendor>.json + <Vendor>/...
  vendors/index.json    [{"id", "name", "models": [...]}] for the vendor picker
  settings_layout.json  desktop settings pages/groups (see extract_settings_layout.py)
  i18n/<lang>.json      msgid -> msgstr of the desktop translations, for option labels/tooltips

Vendors are zipped separately so the app only extracts the ones the user installs; loading all
~100 vendors into a PresetBundle would take far too long on a tablet.
"""
import argparse
import json
import os
import subprocess
import sys
import zipfile
from pathlib import Path

# Subdirectories of resources/ that the slicing core reads at runtime.
RUNTIME_DIRS = ["info", "flush", "filament_mixing", "printers", "custom_gcodes", "shapes"]
# Always installed: every vendor's filaments may inherit from it.
FILAMENT_LIBRARY = "OrcaFilamentLibrary"


def zip_tree(zf: zipfile.ZipFile, root: Path, arc_prefix: str) -> None:
    for dirpath, _, filenames in os.walk(root):
        for name in sorted(filenames):
            path = Path(dirpath) / name
            zf.write(path, f"{arc_prefix}/{path.relative_to(root).as_posix()}")


def model_nozzles(vendor_dir: Path, sub_path: str) -> list:
    """Nozzle diameters offered for a printer model, e.g. ["0.2", "0.4"]."""
    try:
        with open(vendor_dir / sub_path, encoding="utf-8") as f:
            nozzles = json.load(f).get("nozzle_diameter", "")
    except (OSError, ValueError):
        return []
    values = [n.strip() for n in str(nozzles).split(";") if n.strip()]
    return sorted(set(values), key=lambda n: float(n) if n.replace(".", "", 1).isdigit() else 0.0)


def vendor_entry(vendor_json: Path) -> dict:
    with open(vendor_json, encoding="utf-8") as f:
        data = json.load(f)
    vendor_dir = vendor_json.with_suffix("")
    return {
        "id": vendor_json.stem,
        "name": data.get("name", vendor_json.stem),
        "models": [{"name": m.get("name", ""), "nozzles": model_nozzles(vendor_dir, m.get("sub_path", ""))}
                   for m in data.get("machine_model_list", [])],
        "required": vendor_json.stem == FILAMENT_LIBRARY,
    }


def unquote(s: str) -> str:
    return json.loads(s) if s.startswith('"') else s


def parse_po(path: Path) -> dict:
    """Minimal .po reader: singular, non-fuzzy, translated entries only."""
    entries, msgid, msgstr, target, fuzzy = {}, None, None, None, False

    def flush():
        if msgid and msgstr and not fuzzy:
            entries[msgid] = msgstr

    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("#,"):
            fuzzy = "fuzzy" in line
        elif line.startswith("msgid "):
            flush()
            msgid, msgstr, target = unquote(line[6:]), None, "id"
        elif line.startswith("msgstr "):
            msgstr, target = unquote(line[7:]), "str"
        elif line.startswith("msgid_plural") or line.startswith("msgstr["):
            target = None  # plural forms are not used for option labels
            if line.startswith("msgstr[0] "):
                msgstr = unquote(line[10:])
        elif line.startswith('"') and target == "id":
            msgid += unquote(line)
        elif line.startswith('"') and target == "str":
            msgstr += unquote(line)
        elif not line.strip():
            flush()
            msgid, msgstr, target, fuzzy = None, None, None, False
    flush()
    return entries


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--resources", required=True, type=Path, help="src-orca/resources")
    parser.add_argument("--assets", required=True, type=Path, help="app/src/main/assets")
    parser.add_argument("--tab-cpp", type=Path, help="src/slic3r/GUI/Tab.cpp (settings layout)")
    parser.add_argument("--i18n", type=Path, help="localization/i18n (translations)")
    args = parser.parse_args()

    args.assets.mkdir(parents=True, exist_ok=True)
    vendors_dir = args.assets / "vendors"
    vendors_dir.mkdir(exist_ok=True)

    with zipfile.ZipFile(args.assets / "resources.zip", "w", zipfile.ZIP_DEFLATED) as zf:
        for sub in RUNTIME_DIRS:
            if (args.resources / sub).is_dir():
                zip_tree(zf, args.resources / sub, sub)

    profiles = args.resources / "profiles"
    index = []
    for vendor_json in sorted(profiles.glob("*.json")):
        vendor_dir = profiles / vendor_json.stem
        if not vendor_dir.is_dir():
            continue  # e.g. blacklist.json
        entry = vendor_entry(vendor_json)
        with zipfile.ZipFile(vendors_dir / f"{entry['id']}.zip", "w", zipfile.ZIP_DEFLATED) as zf:
            zf.write(vendor_json, vendor_json.name)
            zip_tree(zf, vendor_dir, vendor_json.stem)
        index.append(entry)

    with open(vendors_dir / "index.json", "w", encoding="utf-8") as f:
        json.dump(index, f, ensure_ascii=False, indent=1)

    if args.tab_cpp:
        subprocess.run([sys.executable, str(Path(__file__).with_name("extract_settings_layout.py")),
                        str(args.tab_cpp), str(args.assets / "settings_layout.json")], check=True)

    if args.i18n:
        i18n_out = args.assets / "i18n"
        i18n_out.mkdir(exist_ok=True)
        for po in sorted(args.i18n.glob("*/OrcaSlicer_*.po")):
            lang = po.parent.name
            with open(i18n_out / f"{lang}.json", "w", encoding="utf-8") as f:
                json.dump(parse_po(po), f, ensure_ascii=False, separators=(",", ":"))
        print(f"Packed {len(list(i18n_out.glob('*.json')))} translations")
    print(f"Packed {len(index)} vendors into {vendors_dir}")


if __name__ == "__main__":
    main()
