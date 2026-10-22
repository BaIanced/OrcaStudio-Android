#!/usr/bin/env python3
"""Desktop-compat checker for 3MF files saved by the app.

Turns the Task 6 review (MUSE-REVIEW-0006.md) into a runnable tool: given one .3mf,
it reports every load-time check desktop OrcaSlicer applies that can be decided from
the file alone, so the "desktop rejects the app's 3MF" bug can be tested without a
desktop install.

usage: check_3mf.py <file.3mf>

Prints one line per check: ``OK|FAIL|WARN <check id> <detail>``. Exit 0 if no check
FAILs, 1 otherwise (2 on a usage error). Standard library only; no network.

The per-filament rule, copied from ``PresetBundle::load_config_file_config``
(src-orca/src/libslic3r/PresetBundle.cpp), not paraphrased:

- ``num_filaments`` is the size of ``filament_colour``; zero throws
  "Invalid configuration file" (PresetBundle.cpp:5363-5367).
- Healing before the throw check: a missing ``filament_self_index`` is created at
  ``num_filaments`` with values 1..n (PresetBundle.cpp:5407-5412); a missing
  ``filament_extruder_variant``, or one shorter than ``num_filaments``, is grown to
  ``num_filaments`` (PresetBundle.cpp:5416-5420). A present vector is never resized
  by this healing.
- Only if the config carries ``extruder_variant_list`` (PresetBundle.cpp:5421):
  let V be the healed ``filament_extruder_variant`` size. The load throws
  "Invalid configuration file" iff V != the healed ``filament_self_index`` size
  or V < ``num_filaments`` (PresetBundle.cpp:5421-5429).

Checks that need the desktop's installed presets cannot be decided from the file;
they print WARN with the reason. UNVERIFIED until run on a real app-saved 3MF.
"""
import json
import posixpath
import sys
import zipfile
import xml.etree.ElementTree as ET
from collections import namedtuple
from pathlib import Path

Result = namedtuple("Result", "status check_id detail")

RELS_FILE = "_rels/.rels"
# File names the importer matches by name (src-orca/src/libslic3r/Format/bbs_3mf.cpp:227-228).
PROJECT_CONFIG = "Metadata/project_settings.config"
MODEL_CONFIG = "Metadata/model_settings.config"
# Default model part; the name the exporter writes (bbs_3mf.cpp:209).
MODEL_FILE = "3D/3dmodel.model"

# Per-filament vectors load_config_file_config resize()s to a size derived from
# num_filaments instead of rejecting a mismatch (PresetBundle.cpp:5386-5390, and
# filament_settings_id at PresetBundle.cpp:5506): key -> size as a multiple/offset.
HEALED_VECTORS = {
    "compatible_machine_expression_group": lambda n: n + 2,
    "compatible_process_expression_group": lambda n: n,
    "inherits_group": lambda n: n + 2,
    "different_settings_to_system": lambda n: n + 2,
    "filament_ids": lambda n: n,
    "filament_settings_id": lambda n: n,
}

# Per-variant filament options the multi-filament scatter resize()s to
# extruder_variant_count when process_multi_extruder (PresetBundle.cpp:5552-5556);
# the set itself is src-orca/src/libslic3r/PrintConfig.cpp:9524.
VARIANT_VECTORS = {
    "filament_flow_ratio",
    "filament_max_volumetric_speed",
    "filament_ramming_volumetric_speed",
    "filament_pre_cooling_temperature",
    "filament_ramming_travel_time",
    "filament_ramming_volumetric_speed_nc",
    "filament_pre_cooling_temperature_nc",
    "filament_ramming_travel_time_nc",
    "filament_retract_length_nc",
    "filament_preheat_temperature_delta",
    "filament_retraction_length",
    "filament_z_hop",
    "filament_z_hop_types",
    "filament_retract_lift_above",
    "filament_retract_lift_below",
    "filament_retract_lift_enforce",
    "filament_retract_restart_extra",
    "filament_retract_length_toolchange",
    "filament_retract_restart_extra_toolchange",
    "filament_retraction_speed",
    "filament_deretraction_speed",
    "filament_retraction_minimum_travel",
    "filament_retract_when_changing_layer",
    "filament_wipe",
    "filament_wipe_distance",
    "filament_retract_before_wipe",
    "filament_retract_after_wipe",
    "filament_long_retractions_when_cut",
    "filament_retraction_distances_when_cut",
    "long_retractions_when_ec",
}

# Plate metadata keys in model_settings.config whose values are archive file paths
# (constants at bbs_3mf.cpp:375-381, consumed at bbs_3mf.cpp:4606-4628). A missing
# gcode file breaks a sliced plate; the reader ignores a failed thumbnail extract
# (bbs_3mf.cpp:1730-1733), so the image-like keys are WARN only.
PLATE_FILE_KEYS = {
    "gcode_file": "FAIL",
    "thumbnail_file": "WARN",
    "thumbnail_no_light_file": "WARN",
    "top_file": "WARN",
    "pick_file": "WARN",
    "pattern_file": "WARN",
    "pattern_bbox_file": "WARN",
}


def _local(tag: str) -> str:
    return tag.rpartition("}")[2]


def _vector_values(value) -> list:
    return list(value) if isinstance(value, list) else [value]


def _vector_len(value):
    """Length of a config vector; a scalar in the JSON counts as one value."""
    if value is None:
        return None
    return len(value) if isinstance(value, list) else 1


def _as_int(value):
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.strip().lstrip("-").isdigit():
        return int(value)
    return None


class Archive:
    """Case-insensitive view of a 3MF zip: the importer matches entry names with
    iequals and normalises backslashes (bbs_3mf.cpp:1652-1656)."""

    def __init__(self, zf: zipfile.ZipFile):
        self._zf = zf
        self.names = [name.replace("\\", "/").lstrip("/") for name in zf.namelist()]
        self._by_lower = {}
        for raw, name in zip(zf.namelist(), self.names):
            self._by_lower.setdefault(name.lower(), raw)

    def __contains__(self, name: str) -> bool:
        return name.replace("\\", "/").lstrip("/").lower() in self._by_lower

    def read(self, name: str):
        raw = self._by_lower.get(name.replace("\\", "/").lstrip("/").lower())
        return None if raw is None else self._zf.read(raw)


def _parse_relationships(data: bytes):
    """(target, type) pairs from a .rels part; Type/Target are plain attributes
    (bbs_3mf.cpp:5049-5055)."""
    root = ET.fromstring(data)
    pairs = []
    for element in root.iter():
        if _local(element.tag) == "Relationship":
            pairs.append((element.get("Target", ""), element.get("Type", "")))
    return pairs


def _is_model_relationship(rel_type: str) -> bool:
    # Mirrors _handle_start_relationship (bbs_3mf.cpp:5049-5055).
    return rel_type.startswith("http://schemas.microsoft.com/3dmanufacturing/") and rel_type.endswith("3dmodel")


def _resolve(base_dir: str, target: str) -> str:
    target = target.replace("\\", "/")
    if target.startswith("/"):
        return posixpath.normpath(target.lstrip("/"))
    return posixpath.normpath(posixpath.join(base_dir, target)) if base_dir else posixpath.normpath(target)


def _count_geometry(model_data: bytes):
    """(objects, objects_with_geometry, vertices, triangles) over one .model part.

    An object has geometry when its mesh has vertices and triangles; the importer's
    own Geometry::empty() is ``vertices.empty() || triangles.empty()``
    (bbs_3mf.cpp:846).
    """
    root = ET.fromstring(model_data)
    objects = with_geometry = vertices = triangles = 0
    for element in root.iter():
        if _local(element.tag) != "object":
            continue
        objects += 1
        obj_vertices = obj_triangles = 0
        for descendant in element.iter():
            name = _local(descendant.tag)
            if name == "vertex":
                obj_vertices += 1
            elif name == "triangle":
                obj_triangles += 1
        vertices += obj_vertices
        triangles += obj_triangles
        if obj_vertices > 0 and obj_triangles > 0:
            with_geometry += 1
    return objects, with_geometry, vertices, triangles


def check_file(path: Path) -> list:
    results = []

    def add(status, check_id, detail):
        results.append(Result(status, check_id, detail))

    if not path.is_file():
        add("FAIL", "archive_readable", f"no such file: {path}")
        return results
    try:
        zf = zipfile.ZipFile(path)
        bad = zf.testzip()
        if bad is not None:
            raise zipfile.BadZipFile(f"corrupt entry: {bad}")
    except (zipfile.BadZipFile, OSError) as exc:
        # The importer records "Unable to open the zipfile" and fails the load
        # (bbs_3mf.cpp:1068).
        add("FAIL", "archive_readable", f"not a readable zip archive: {exc}")
        return results

    with zf:
        archive = Archive(zf)
        add("OK", "archive_readable", f"{len(archive.names)} entries")

        # -- Relationships / start part --------------------------------------
        # The load reads _rels/.rels first and returns false without a start part
        # (bbs_3mf.cpp:1607-1610); the first 3dmodel relationship is the start
        # part, later ones are sub-models (bbs_3mf.cpp:5049-5055).
        rels_pairs = None
        rels_data = archive.read(RELS_FILE)
        if rels_data is None:
            add("FAIL", "relationships_start_part", f"{RELS_FILE} missing; the reader cannot find a start part")
        else:
            try:
                rels_pairs = _parse_relationships(rels_data)
            except ET.ParseError as exc:
                add("FAIL", "relationships_start_part", f"{RELS_FILE} does not parse: {exc}")
        model_targets = []
        if rels_pairs is not None:
            model_targets = [_resolve("", target) for target, rel_type in rels_pairs if _is_model_relationship(rel_type)]
            if model_targets:
                add("OK", "relationships_start_part", f"start part {model_targets[0]}")
            else:
                add("FAIL", "relationships_start_part", "no relationship of type .../3dmodel in _rels/.rels")

        # Sub-model relationships live in <dir>/_rels/<start part>.rels, derived
        # exactly as the reader derives it (bbs_3mf.cpp:1836-1847).
        sub_pairs = []
        sub_base = ""
        if model_targets:
            start = model_targets[0]
            sub_base = posixpath.dirname(start)
            sub_rels = f"{sub_base}/_rels/{posixpath.basename(start)}.rels" if sub_base else f"_rels/{posixpath.basename(start)}.rels"
            sub_data = archive.read(sub_rels)
            if sub_data is not None:
                try:
                    sub_pairs = _parse_relationships(sub_data)
                except ET.ParseError:
                    sub_pairs = []
                model_targets += [_resolve(sub_base, target) for target, rel_type in sub_pairs if _is_model_relationship(rel_type)]

        # -- Referenced files exist -------------------------------------------
        referenced = []
        if rels_pairs is not None:
            referenced += [(_resolve("", target), rel_type) for target, rel_type in rels_pairs if target]
            referenced += [(_resolve(sub_base, target), rel_type) for target, rel_type in sub_pairs if target]
        missing_model = [name for name, rel_type in referenced if name not in archive and _is_model_relationship(rel_type)]
        missing_other = [name for name, rel_type in referenced if name not in archive and not _is_model_relationship(rel_type)]
        if rels_pairs is None:
            add("FAIL", "referenced_files_exist", "no _rels/.rels to resolve references from")
        elif missing_model:
            add("FAIL", "referenced_files_exist", f"model files referenced but absent: {', '.join(missing_model)}")
        elif missing_other:
            add("WARN", "referenced_files_exist", f"non-model files referenced but absent: {', '.join(missing_other)}")
        else:
            add("OK", "referenced_files_exist", f"all {len(referenced)} relationship targets present")

        # -- project_settings.config ------------------------------------------
        # Matched by name at bbs_3mf.cpp:1656 / :2012, written at :7997-7998.
        config_data = archive.read(PROJECT_CONFIG)
        if config_data is None:
            # Review 0006 check 9: missing config is not a rejection; the desktop
            # loads with default presets instead.
            add("WARN", "project_settings_present", f"{PROJECT_CONFIG} absent; desktop would load default presets")
        else:
            add("OK", "project_settings_present", PROJECT_CONFIG)

        # A project_settings.config that fails to parse only records a non-fatal
        # importer error (bbs_3mf.cpp:2746-2768); the fatal checks below are the
        # ones load_config_file_config throws on.
        config = None
        if config_data is None:
            add("WARN", "project_settings_json", "absent; nothing to parse")
        else:
            try:
                parsed = json.loads(config_data.decode("utf-8"))
                if not isinstance(parsed, dict):
                    raise ValueError("top level is not a JSON object")
                config = parsed
                add("OK", "project_settings_json", f"{len(config)} keys")
            except (ValueError, UnicodeDecodeError) as exc:
                add("FAIL", "project_settings_json", f"{PROJECT_CONFIG} does not parse as a JSON object: {exc}")

        num_filaments = None
        if config is None:
            add("WARN", "filament_colour_nonempty", "not decidable: no parsed project_settings.config")
            add("WARN", "filament_variant_lengths", "not decidable: no parsed project_settings.config")
        else:
            # num_filaments comes from filament_colour; zero throws
            # (PresetBundle.cpp:5363-5367).
            colour = config.get("filament_colour")
            num_filaments = _vector_len(colour)
            if not num_filaments:
                add("FAIL", "filament_colour_nonempty",
                    "filament_colour missing or empty: num_filaments = 0, desktop throws "
                    "'Invalid configuration file' (PresetBundle.cpp:5363-5367)")
            else:
                add("OK", "filament_colour_nonempty", f"num_filaments = {num_filaments}")

            # The exact throw rule stated in the module docstring
            # (PresetBundle.cpp:5407-5429).
            if "extruder_variant_list" not in config:
                add("OK", "filament_variant_lengths",
                    "extruder_variant_list absent; desktop skips the throw check (PresetBundle.cpp:5421)")
            elif num_filaments:
                healed_self = num_filaments if "filament_self_index" not in config else _vector_len(config["filament_self_index"])
                variant_len = _vector_len(config.get("filament_extruder_variant"))
                healed_variant = num_filaments if variant_len is None or variant_len < num_filaments else variant_len
                if healed_variant != healed_self or healed_variant < num_filaments:
                    add("FAIL", "filament_variant_lengths",
                        f"after healing filament_extruder_variant = {healed_variant}, filament_self_index = "
                        f"{healed_self}, num_filaments = {num_filaments}: desktop throws "
                        "'Invalid configuration file' (PresetBundle.cpp:5421-5429)")
                else:
                    add("OK", "filament_variant_lengths",
                        f"filament_extruder_variant = filament_self_index = {healed_variant} after healing")
            else:
                add("WARN", "filament_variant_lengths", "not decidable: num_filaments unknown")

        # -- Other per-filament vectors ---------------------------------------
        # Every other per-filament vector in load_config_file_config is resize()d,
        # never rejected (PresetBundle.cpp:5386-5390, :5506, :5552-5556), so a
        # mismatch is a WARN: the desktop silently pads/truncates it.
        if config is None or not num_filaments:
            add("WARN", "per_filament_vectors", "not decidable: filament count unknown")
        else:
            mismatches = []
            for key, expected in HEALED_VECTORS.items():
                if key in config and _vector_len(config[key]) != expected(num_filaments):
                    mismatches.append(f"{key} {_vector_len(config[key])} -> {expected(num_filaments)}")
            if "extruder_variant_list" in config and num_filaments > 1:
                variant_len = _vector_len(config.get("filament_extruder_variant"))
                variant_count = num_filaments if variant_len is None or variant_len < num_filaments else variant_len
                for key in sorted(VARIANT_VECTORS):
                    if key in config and _vector_len(config[key]) != variant_count:
                        mismatches.append(f"{key} {_vector_len(config[key])} -> {variant_count}")
            if mismatches:
                add("WARN", "per_filament_vectors",
                    "desktop resize()s these per-filament vectors without rejecting: " + "; ".join(mismatches))
            else:
                add("OK", "per_filament_vectors", "all present per-filament vectors already at the sizes the desktop resizes to")

        # -- Task 6 suspected item ---------------------------------------------
        # match_filament_indices rewrites a mismatched filament_self_index to the
        # identity (review 0006 check 3). Decidable from the file only when the
        # nozzle count is in the file: a non-identity self index on a single-nozzle
        # printer (nozzle_diameter has one entry, PresetBundle.cpp:5423) has no
        # legitimate variant mapping to be.
        if config is None or "filament_self_index" not in config:
            add("OK", "filament_self_index_identity",
                "filament_self_index absent or no config; desktop creates the identity 1..n (PresetBundle.cpp:5407-5412)")
        elif "nozzle_diameter" not in config:
            add("WARN", "filament_self_index_identity",
                "cannot decide single-nozzle: nozzle_diameter absent from the config")
        elif _vector_len(config["nozzle_diameter"]) != 1:
            add("OK", "filament_self_index_identity",
                f"nozzle_diameter has {_vector_len(config['nozzle_diameter'])} entries (multi-nozzle); "
                "a non-identity self index may be a legitimate variant mapping")
        else:
            values = [_as_int(v) for v in _vector_values(config["filament_self_index"])]
            if values == list(range(1, len(values) + 1)):
                add("OK", "filament_self_index_identity", "filament_self_index is the identity on a single-nozzle printer")
            else:
                add("WARN", "filament_self_index_identity",
                    f"filament_self_index {values} is not the identity on a single-nozzle printer "
                    "(review 0006 check 3: a real variant mapping would be silently replaced)")

        # -- Model files / geometry ---------------------------------------------
        # The reader opens the start part plus every sub-model from the sub-rels
        # (bbs_3mf.cpp:1614-1618, :1847-1916). Zero committed objects is what makes
        # the desktop GUI report "The file does not contain any geometry data."
        # (src-orca/src/slic3r/GUI/Plater.cpp:10225-10232).
        if not model_targets:
            add("FAIL", "model_geometry", "no model file to open: no start part from _rels/.rels")
        else:
            problems = []
            total_objects = total_with_geometry = 0
            for name in model_targets:
                data = archive.read(name)
                if data is None:
                    problems.append(f"{name} referenced but absent")
                    continue
                try:
                    objects, with_geometry, _, _ = _count_geometry(data)
                except ET.ParseError as exc:
                    problems.append(f"{name} does not parse: {exc}")
                    continue
                total_objects += objects
                total_with_geometry += with_geometry
            if problems:
                add("FAIL", "model_geometry", "; ".join(problems))
            elif total_with_geometry == 0:
                add("FAIL", "model_geometry",
                    f"{total_objects} object(s), none with vertices and triangles: desktop reports "
                    "'The file does not contain any geometry data.' (Plater.cpp:10225-10232)")
            else:
                add("OK", "model_geometry",
                    f"{total_with_geometry} of {total_objects} object(s) have vertices and triangles")

        # -- model_settings.config ----------------------------------------------
        # A malformed model_settings.config fails the whole load in the BBS flow
        # (bbs_3mf.cpp:1661-1666); the exporter always writes one (bbs_3mf.cpp:8392).
        model_config_root = None
        model_config_data = archive.read(MODEL_CONFIG)
        if model_config_data is None:
            add("WARN", "model_settings_parses", f"{MODEL_CONFIG} absent; plate/object settings would be missing")
        else:
            try:
                model_config_root = ET.fromstring(model_config_data)
                add("OK", "model_settings_parses", MODEL_CONFIG)
            except ET.ParseError as exc:
                add("FAIL", "model_settings_parses", f"{MODEL_CONFIG} does not parse: {exc}")

        # -- Files referenced from model_settings plates --------------------------
        if model_config_root is None:
            add("WARN", "plate_referenced_files", "no parsed model_settings.config to read plate references from")
        else:
            missing_fail, missing_warn = [], []
            referenced_count = 0
            for plate in model_config_root.iter():
                if _local(plate.tag) != "plate":
                    continue
                for element in plate.iter():
                    if _local(element.tag) != "metadata":
                        continue
                    key, value = element.get("key", ""), element.get("value", "")
                    if key not in PLATE_FILE_KEYS or not value:
                        continue
                    referenced_count += 1
                    candidate = value.replace("\\", "/").lstrip("/")
                    present = candidate in archive or any(name.endswith("/" + candidate) for name in archive.names)
                    if not present:
                        (missing_fail if PLATE_FILE_KEYS[key] == "FAIL" else missing_warn).append(candidate)
            if missing_fail:
                add("FAIL", "plate_referenced_files", f"plate files referenced but absent: {', '.join(missing_fail)}")
            elif missing_warn:
                add("WARN", "plate_referenced_files", f"plate files referenced but absent: {', '.join(missing_warn)}")
            else:
                add("OK", "plate_referenced_files", f"all {referenced_count} plate file reference(s) present")

        # -- Preset names ---------------------------------------------------------
        # print/printer/filament preset ids are resolved against the presets
        # installed on the desktop (the load_preset lambda and its calls,
        # PresetBundle.cpp:5460-5474, :5494, :5502, :5528), so whether they exist
        # there cannot be decided from this file.
        if config is None:
            add("WARN", "preset_names_resolvable", "not decidable: no parsed project_settings.config")
        else:
            preset_ids = []
            for key in ("print_settings_id", "printer_settings_id"):
                if config.get(key):
                    preset_ids.append(str(config[key]))
            if config.get("filament_settings_id"):
                preset_ids += [str(v) for v in _vector_values(config["filament_settings_id"])]
            if preset_ids:
                add("WARN", "preset_names_resolvable",
                    f"preset ids {', '.join(preset_ids)} resolve against the desktop's installed presets, "
                    "not decidable from the file alone")
            else:
                add("OK", "preset_names_resolvable", "no preset ids referenced")

    return results


def main(argv) -> int:
    if len(argv) != 2:
        print("usage: check_3mf.py <file.3mf>", file=sys.stderr)
        return 2
    results = check_file(Path(argv[1]))
    for result in results:
        print(f"{result.status} {result.check_id} {result.detail}")
    return 1 if any(result.status == "FAIL" for result in results) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
