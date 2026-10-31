"""Offline harness for the storage migration planner and quarantine behavior."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


TOOL = Path(__file__).with_name("migrate_storage.py")
OWNER = "11111111-1111-4111-8111-111111111111"
COVER = "22222222-2222-4222-8222-222222222222.jpg"


class StorageMigrationHarness(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source_data = self.root / "source-data"
        self.source_media = self.root / "source-media"
        self.backup = self.root / "backup"
        self.destination = self.root / "destination"
        self.report = self.root / "report.json"
        self.covers = self.source_media / "reading-library" / "covers"
        self.covers.mkdir(parents=True)
        self.source_data.mkdir()
        (self.source_data / "hub.mv.db").write_bytes(b"offline database snapshot")
        shutil.copytree(self.source_data, self.backup / "hub_data")
        shutil.copytree(self.source_media, self.backup / "hub_media")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_tool(self, index: dict, *, apply: bool = False) -> dict:
        for source, backup_name in ((self.source_data, "hub_data"), (self.source_media, "hub_media")):
            backup_path = self.backup / backup_name
            if backup_path.exists():
                shutil.rmtree(backup_path)
            shutil.copytree(source, backup_path)
        index_path = self.root / "cover-index.json"
        index_path.write_text(json.dumps(index), encoding="utf-8")
        command = [
            sys.executable, str(TOOL),
            "--source-data", str(self.source_data),
            "--source-media", str(self.source_media),
            "--backup-root", str(self.backup),
            "--cover-index", str(index_path),
            "--destination", str(self.destination),
            "--report", str(self.report),
            "--backend-stopped", "--backup-verified",
        ]
        if apply:
            command.append("--apply")
        result = subprocess.run(command, check=False, capture_output=True, text=True)
        report = json.loads(self.report.read_text(encoding="utf-8"))
        report["exitCode"] = result.returncode
        report["stdout"] = result.stdout
        report["stderr"] = result.stderr
        return report

    def test_empty_owner_map_is_valid_and_quarantines_orphan_files(self) -> None:
        orphan = self.covers / "orphan.jpg"
        orphan.write_bytes(b"unlinked legacy cover")
        orphan_thumb = self.covers / "orphan.thumb.jpg"
        orphan_thumb.write_bytes(b"unlinked thumbnail")

        report = self.run_tool({"owners": {}})

        self.assertEqual(report["result"], "ready-for-apply")
        self.assertEqual(report["exitCode"], 0)
        self.assertEqual({item["file"] for item in report["orphans"]}, {"orphan.jpg", "orphan.thumb.jpg"})
        for item in report["orphans"]:
            self.assertEqual(item["sha256"], hashlib.sha256((self.covers / item["file"]).read_bytes()).hexdigest())
            self.assertTrue(item["quarantineTarget"].startswith("quarantine/legacy-reading-covers/"))
            self.assertIn("quarantine", item["quarantineTarget"])

    def test_referenced_original_and_thumbnail_are_not_orphans_and_true_orphans_are_copied_idempotently(self) -> None:
        original = self.covers / COVER
        original.write_bytes(b"referenced image")
        thumb = self.covers / "22222222-2222-4222-8222-222222222222.thumb.jpg"
        thumb.write_bytes(b"generated thumbnail")
        orphan = self.covers / "orphan.bin"
        orphan.write_bytes(b"unlinked")
        index = {"owners": {OWNER: {COVER: hashlib.sha256(original.read_bytes()).hexdigest()}}}

        planned = self.run_tool(index)

        self.assertEqual(planned["result"], "ready-for-apply")
        self.assertEqual([item["file"] for item in planned["orphans"]], ["orphan.bin"])
        quarantine_target = self.destination / planned["orphans"][0]["quarantineTarget"]
        self.assertFalse(quarantine_target.exists())
        self.assertEqual(self.run_tool(index, apply=True)["result"], "copied-no-cutover")
        self.assertEqual(quarantine_target.read_bytes(), orphan.read_bytes())
        self.assertTrue(original.is_file())
        self.assertTrue(thumb.is_file())
        self.assertTrue(orphan.is_file())
        repeated = self.run_tool(index, apply=True)
        self.assertEqual(repeated["result"], "copied-no-cutover")
        self.assertIn("already quarantined", repeated["orphans"][0]["action"])

    def test_cover_index_rejects_traversal_names(self) -> None:
        index_path = self.root / "cover-index.json"
        index_path.write_text(json.dumps({"owners": {OWNER: {"../cover.jpg": "0" * 64}}}), encoding="utf-8")
        from tools.migrate_storage import validate_cover_index

        with self.assertRaisesRegex(ValueError, "Invalid cover name/hash"):
            validate_cover_index(index_path)

    def test_cover_index_rejects_duplicate_json_keys(self) -> None:
        index_path = self.root / "cover-index.json"
        index_path.write_text('{"owners":{},"owners":{}}', encoding="utf-8")
        from tools.migrate_storage import validate_cover_index

        with self.assertRaisesRegex(ValueError, "Duplicate key"):
            validate_cover_index(index_path)

    def test_cover_index_rejects_symlink(self) -> None:
        from tools.migrate_storage import validate_cover_index

        target = self.root / "real-index.json"
        target.write_text('{"owners":{}}', encoding="utf-8")
        link = self.root / "linked-index.json"
        try:
            link.symlink_to(target)
        except (NotImplementedError, OSError) as error:
            self.skipTest(f"Symlink creation is unavailable: {error}")

        with self.assertRaisesRegex(ValueError, "Symlink cover index"):
            validate_cover_index(link)

    def test_safe_target_rejects_path_traversal(self) -> None:
        from tools.migrate_storage import safe_target

        with self.assertRaisesRegex(ValueError, "Unsafe target path"):
            safe_target(self.destination, "../outside")


if __name__ == "__main__":
    unittest.main()
