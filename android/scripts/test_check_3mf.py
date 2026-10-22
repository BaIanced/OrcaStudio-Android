#!/usr/bin/env python3
"""Unit tests for check_3mf.py.

Fixtures are minimal 3MF zips built here (in a temp dir per test): one passing
file and one per FAIL case, plus the WARN cases the desktop heals or ignores.
Run with: python3 -m unittest android/scripts/test_check_3mf.py
"""
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("check_3mf.py")
_spec = importlib.util.spec_from_file_location("check_3mf", SCRIPT)
check_3mf = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check_3mf)

MODEL = """<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xml:lang="en-US"
 xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
 <resources>
  <object id="1" type="model">
   <mesh>
    <vertices>
     <vertex x="0" y="0" z="0"/><vertex x="1" y="0" z="0"/><vertex x="0" y="1" z="0"/>
    </vertices>
    <triangles><triangle v1="0" v2="1" v3="2"/></triangles>
   </mesh>
  </object>
 </resources>
 <build><item objectid="1"/></build>
</model>
"""

MODEL_NO_GEOMETRY = MODEL.replace(
    '<triangles><triangle v1="0" v2="1" v3="2"/></triangles>', "<triangles/>")

RELS = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Target="/3D/3dmodel.model" Id="rel-1"
  Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
 <Relationship Target="/Metadata/plate_1.png" Id="rel-2"
  Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail"/>
</Relationships>
"""

MODEL_SETTINGS = """<?xml version="1.0" encoding="UTF-8"?>
<config>
 <object id="1"><metadata key="name" value="Cube"/></object>
 <plate>
  <metadata key="plater_id" value="1"/>
  <metadata key="gcode_file" value="Metadata/plate_1.gcode"/>
  <metadata key="thumbnail_file" value="Metadata/plate_1.png"/>
 </plate>
</config>
"""


def passing_config() -> dict:
    return {
        "filament_colour": ["#FF0000", "#00FF00"],
        "filament_self_index": [1, 2],
        "filament_extruder_variant": ["Direct Drive Standard", "Direct Drive Standard"],
        "extruder_variant_list": ["Direct Drive Standard"],
        "nozzle_diameter": ["0.4"],
        "filament_ids": ["F1", "F2"],
        "compatible_process_expression_group": ["", ""],
        "filament_settings_id": ["Filament A", "Filament B"],
        "printer_settings_id": "Printer A",
        "print_settings_id": "Process A",
    }


def passing_entries() -> dict:
    return {
        "_rels/.rels": RELS,
        "3D/3dmodel.model": MODEL,
        "Metadata/project_settings.config": json.dumps(passing_config()),
        "Metadata/model_settings.config": MODEL_SETTINGS,
        "Metadata/plate_1.gcode": "; placeholder gcode\n",
        "Metadata/plate_1.png": b"\x89PNG\r\n",
    }


class Check3mfTest(unittest.TestCase):
    def build(self, entries) -> Path:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        path = Path(tmp.name) / "fixture.3mf"
        if isinstance(entries, bytes):
            path.write_bytes(entries)
        else:
            with zipfile.ZipFile(path, "w") as zf:
                for name, data in entries.items():
                    zf.writestr(name, data)
        return path

    def check(self, entries) -> dict:
        results = check_3mf.check_file(self.build(entries))
        return {result.check_id: result for result in results}

    def status(self, entries, check_id) -> str:
        return self.check(entries)[check_id].status

    def with_config(self, mutate) -> dict:
        entries = passing_entries()
        config = passing_config()
        mutate(config)
        entries["Metadata/project_settings.config"] = json.dumps(config)
        return entries

    # -- passing / CLI ------------------------------------------------------

    def test_passing_file_has_no_fail(self):
        results = self.check(passing_entries())
        self.assertNotIn("FAIL", [r.status for r in results.values()])

    def test_cli_passing_exit_0_and_line_format(self):
        path = self.build(passing_entries())
        proc = subprocess.run([sys.executable, str(SCRIPT), str(path)],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        for line in proc.stdout.splitlines():
            status, check_id, _ = line.split(" ", 2)
            self.assertIn(status, ("OK", "FAIL", "WARN"))
            self.assertTrue(check_id)

    def test_cli_failing_exit_1(self):
        entries = self.with_config(lambda c: c.update(filament_colour=[]))
        proc = subprocess.run([sys.executable, str(SCRIPT), str(self.build(entries))],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("FAIL filament_colour_nonempty", proc.stdout)

    def test_cli_usage_error_exit_2(self):
        proc = subprocess.run([sys.executable, str(SCRIPT)], capture_output=True, text=True)
        self.assertEqual(proc.returncode, 2)

    def test_cli_missing_file_exit_1(self):
        proc = subprocess.run([sys.executable, str(SCRIPT), "/nonexistent/x.3mf"],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 1)
        self.assertIn("FAIL archive_readable", proc.stdout)

    # -- FAIL cases -----------------------------------------------------------

    def test_not_a_zip(self):
        self.assertEqual(self.status(b"this is not a zip", "archive_readable"), "FAIL")

    def test_missing_rels(self):
        entries = passing_entries()
        del entries["_rels/.rels"]
        self.assertEqual(self.status(entries, "relationships_start_part"), "FAIL")

    def test_missing_model_file(self):
        entries = passing_entries()
        del entries["3D/3dmodel.model"]
        results = self.check(entries)
        self.assertEqual(results["referenced_files_exist"].status, "FAIL")
        self.assertEqual(results["model_geometry"].status, "FAIL")

    def test_model_without_geometry(self):
        entries = passing_entries()
        entries["3D/3dmodel.model"] = MODEL_NO_GEOMETRY
        self.assertEqual(self.status(entries, "model_geometry"), "FAIL")

    def test_model_unparseable(self):
        entries = passing_entries()
        entries["3D/3dmodel.model"] = "<model><resources>"
        self.assertEqual(self.status(entries, "model_geometry"), "FAIL")

    def test_submodel_referenced_but_absent(self):
        entries = passing_entries()
        entries["3D/_rels/3dmodel.model.rels"] = """<?xml version="1.0"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Target="/3D/Objects/object_1.model" Id="rel-1"
  Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>
"""
        results = self.check(entries)
        self.assertEqual(results["referenced_files_exist"].status, "FAIL")
        self.assertEqual(results["model_geometry"].status, "FAIL")

    def test_project_settings_invalid_json(self):
        entries = passing_entries()
        entries["Metadata/project_settings.config"] = "{not json"
        self.assertEqual(self.status(entries, "project_settings_json"), "FAIL")

    def test_project_settings_not_an_object(self):
        entries = passing_entries()
        entries["Metadata/project_settings.config"] = '["a", "b"]'
        self.assertEqual(self.status(entries, "project_settings_json"), "FAIL")

    def test_filament_colour_empty(self):
        entries = self.with_config(lambda c: c.update(filament_colour=[]))
        self.assertEqual(self.status(entries, "filament_colour_nonempty"), "FAIL")

    def test_filament_colour_missing(self):
        entries = self.with_config(lambda c: c.pop("filament_colour"))
        self.assertEqual(self.status(entries, "filament_colour_nonempty"), "FAIL")

    def test_variant_lengths_mismatch_throws(self):
        # The pre-fix app shape: a one-entry filament_self_index next to two
        # filaments and a two-entry variant list.
        entries = self.with_config(lambda c: c.update(filament_self_index=[1]))
        self.assertEqual(self.status(entries, "filament_variant_lengths"), "FAIL")

    def test_variant_lengths_self_longer_than_variant(self):
        entries = self.with_config(lambda c: c.update(filament_self_index=[1, 2, 3]))
        self.assertEqual(self.status(entries, "filament_variant_lengths"), "FAIL")

    def test_model_settings_unparseable(self):
        entries = passing_entries()
        entries["Metadata/model_settings.config"] = "<config><plate>"
        self.assertEqual(self.status(entries, "model_settings_parses"), "FAIL")

    def test_plate_gcode_file_missing(self):
        entries = passing_entries()
        del entries["Metadata/plate_1.gcode"]
        self.assertEqual(self.status(entries, "plate_referenced_files"), "FAIL")

    # -- Healing / OK cases the desktop does not reject -------------------------

    def test_missing_self_index_is_healed(self):
        entries = self.with_config(lambda c: c.pop("filament_self_index"))
        self.assertEqual(self.status(entries, "filament_variant_lengths"), "OK")

    def test_short_variant_is_healed_to_filament_count(self):
        entries = self.with_config(
            lambda c: c.update(filament_extruder_variant=["Direct Drive Standard"]))
        # With a one-entry self index too, healing grows the variant to 2 and the
        # two healed sizes still disagree, so use the default self index here:
        # variant heals 1 -> 2, self is 2, no throw.
        self.assertEqual(
            self.status(entries, "filament_variant_lengths"), "OK")

    def test_no_extruder_variant_list_skips_throw_check(self):
        def mutate(c):
            c.pop("extruder_variant_list")
            c.update(filament_self_index=[1])
        entries = self.with_config(mutate)
        self.assertEqual(self.status(entries, "filament_variant_lengths"), "OK")

    def test_submodel_with_geometry_counts(self):
        entries = passing_entries()
        entries["3D/_rels/3dmodel.model.rels"] = """<?xml version="1.0"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Target="/3D/Objects/object_1.model" Id="rel-1"
  Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>
"""
        entries["3D/Objects/object_1.model"] = MODEL
        results = self.check(entries)
        self.assertEqual(results["model_geometry"].status, "OK")
        self.assertIn("2 of 2", results["model_geometry"].detail)

    # -- WARN cases -------------------------------------------------------------

    def test_missing_project_settings_is_warn_not_fail(self):
        entries = passing_entries()
        del entries["Metadata/project_settings.config"]
        results = self.check(entries)
        self.assertEqual(results["project_settings_present"].status, "WARN")

    def test_per_filament_vector_mismatch_is_warn(self):
        entries = self.with_config(lambda c: c.update(filament_ids=["only-one"]))
        results = self.check(entries)
        self.assertEqual(results["per_filament_vectors"].status, "WARN")
        self.assertIn("filament_ids 1 -> 2", results["per_filament_vectors"].detail)

    def test_variant_vector_mismatch_is_warn(self):
        entries = self.with_config(
            lambda c: c.update(filament_flow_ratio=["1.0", "1.0", "1.0"]))
        results = self.check(entries)
        self.assertEqual(results["per_filament_vectors"].status, "WARN")
        self.assertIn("filament_flow_ratio 3 -> 2", results["per_filament_vectors"].detail)

    def test_non_identity_self_index_single_nozzle_is_warn(self):
        entries = self.with_config(lambda c: c.update(filament_self_index=[2, 1]))
        results = self.check(entries)
        self.assertEqual(results["filament_self_index_identity"].status, "WARN")
        self.assertNotIn("FAIL", [r.status for r in results.values()])

    def test_plate_thumbnail_missing_is_warn(self):
        entries = passing_entries()
        del entries["Metadata/plate_1.png"]
        results = self.check(entries)
        # The thumbnail is referenced from both _rels/.rels and the plate
        # metadata; both checks report it as WARN, never FAIL.
        self.assertEqual(results["plate_referenced_files"].status, "WARN")
        self.assertNotIn("FAIL", [r.status for r in results.values()])

    def test_preset_names_are_warn_not_decidable(self):
        results = self.check(passing_entries())
        self.assertEqual(results["preset_names_resolvable"].status, "WARN")
        self.assertIn("Printer A", results["preset_names_resolvable"].detail)


if __name__ == "__main__":
    unittest.main()
