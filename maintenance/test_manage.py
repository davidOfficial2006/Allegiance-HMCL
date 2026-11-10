"""The promotion gate must never replace an app on incomplete or mismatched acceptance."""
import copy
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import zipfile
import manage


class PromotionTests(unittest.TestCase):
    def test_empty_source_zip_cannot_be_accepted_as_a_source_snapshot(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "source.zip"
            with zipfile.ZipFile(path, "w"):
                pass
            with self.assertRaises(ValueError): manage.check_source_archive(path)

    def test_pending_missing_and_wrong_artifact_are_rejected(self):
        metadata = {"version": "test", "jar": "test.jar", "artifacts": {"test.jar": "abc"}}
        acceptance = {"version": "test", "jar_sha256": "abc", "checks": {}}
        with self.assertRaises(ValueError): manage.promotion_ready(metadata, acceptance)
        for key in manage.CHECKS:
            acceptance["checks"][key] = {"status": "passed", "tester": "test operator", "note": "fixture only", "tested_at": "2026-11-02"}
        manage.promotion_ready(metadata, acceptance)
        wrong = copy.deepcopy(acceptance)
        wrong["jar_sha256"] = "different"
        with self.assertRaises(ValueError): manage.promotion_ready(metadata, wrong)
        acceptance["checks"]["launch_and_join"]["status"] = "failed"
        with self.assertRaises(ValueError): manage.promotion_ready(metadata, acceptance)

    def test_candidate_with_pending_acceptance_leaves_launcher_untouched(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            folder = root / "releases/test"
            folder.mkdir(parents=True)
            app = root / "app"
            app.mkdir()
            launcher = app / "start-local-sync.cmd"
            launcher.write_text("original launcher", encoding="ascii")
            jar = folder / "Allegiance-HMCL-test.jar"
            jar.write_bytes(b"test-only fixture")
            manage.write(folder / "release.json", {"status": "candidate", "version": "test", "jar": jar.name, "artifacts": {jar.name: manage.sha(jar)}})
            manage.write(folder / "acceptance.json", {"version": "test", "jar_sha256": manage.sha(jar), "checks": {}})
            with patch.object(manage, "RELEASES", root / "releases"), patch.object(manage, "APP", app):
                with self.assertRaises(ValueError): manage.promote("test")
            self.assertEqual(launcher.read_text(), "original launcher")
            self.assertFalse((app / jar.name).exists())

    def test_verified_versions_switch_and_rollback_without_overwriting_old_jars(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            app = root / "app"
            app.mkdir()
            launcher = app / "start-local-sync.cmd"
            launcher.write_text('start "" javaw -jar "Allegiance-HMCL-legacy.jar"\n', encoding="ascii")
            for version in ("one", "two"):
                folder = root / "releases" / version
                folder.mkdir(parents=True)
                jar = folder / ("Allegiance-HMCL-" + version + ".jar")
                jar.write_bytes(version.encode())
                manage.write(folder / "release.json", {"status": "candidate", "version": version, "jar": jar.name, "artifacts": {jar.name: manage.sha(jar)}})
                manage.write(folder / "acceptance.json", {"version": version, "jar_sha256": manage.sha(jar), "checks": {
                    key: {"status": "passed", "tester": "fixture", "note": "isolated test, not real game acceptance", "tested_at": "2026-11-02"}
                    for key in manage.CHECKS}})
            with patch.object(manage, "RELEASES", root / "releases"), patch.object(manage, "APP", app):
                manage.promote("one")
                manage.promote("two")
                self.assertIn("Allegiance-HMCL-two.jar", launcher.read_text())
                manage.promote("one")
            self.assertIn("Allegiance-HMCL-one.jar", launcher.read_text())
            self.assertEqual((app / "Allegiance-HMCL-one.jar").read_bytes(), b"one")
            self.assertEqual((app / "Allegiance-HMCL-two.jar").read_bytes(), b"two")
            self.assertEqual(len(list((app / "维护切换记录").glob("*.cmd"))), 3)


if __name__ == "__main__":
    unittest.main()
