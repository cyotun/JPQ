import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile


MODULE_PATH = Path(__file__).with_name("compact-phone-test-evidence.py")
SPEC = importlib.util.spec_from_file_location("compact_phone_test_evidence", MODULE_PATH)
compact = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(compact)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class CompactPhoneEvidenceTests(unittest.TestCase):
    def setUp(self):
        workspace_tmp = Path(__file__).resolve().parents[2] / "tmp"
        workspace_tmp.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=workspace_tmp)
        self.repo = Path(self.temp.name)
        self.phone = self.repo / "captures" / "phone"
        self.allowed = self.phone / "candidate-c3-test"
        self.allowed.mkdir(parents=True)
        self.source = self.allowed / "diagnostics.zip"
        self.target = self.allowed / "diagnostics.compact.zip"
        self.manifest = self.allowed / "diagnostics.compact.manifest.json"
        self.members = {
            "sessions/s1/trace.jsonl": b'{"captureScope":"APP_TASK"}\n',
            "capture-probes/p1/probe.json": b'{"probeId":"p1"}\n',
            "other/not-selected.bin": b"keep out",
        }
        with zipfile.ZipFile(self.source, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in self.members.items():
                archive.writestr(name, data)

    def tearDown(self):
        self.temp.cleanup()

    def plan(self, members=None, **overrides):
        operation = {
            "operation": "compact",
            "evidenceClass": "non_game",
            "sourceZip": "candidate-c3-test/diagnostics.zip",
            "expectedSourceSha256": compact.sha256_file(self.source),
            "targetZip": "candidate-c3-test/diagnostics.compact.zip",
            "manifestPath": "candidate-c3-test/diagnostics.compact.manifest.json",
            "keepMembers": members or [
                {"path": name, "sha256": digest(data)}
                for name, data in self.members.items() if name != "other/not-selected.bin"
            ],
        }
        operation.update(overrides)
        return {"version": 1, "operations": [operation]}

    def test_dry_run_does_not_write_or_remove(self):
        result = compact.execute_plan(self.plan(), self.repo, apply=False)
        self.assertEqual(len(result), 1)
        self.assertTrue(self.source.exists())
        self.assertFalse(self.target.exists())
        self.assertFalse(self.manifest.exists())

    def test_changed_source_sha_is_rejected(self):
        plan = self.plan(expectedSourceSha256="0" * 64)
        with self.assertRaisesRegex(compact.PlanError, "SHA-256 changed"):
            compact.execute_plan(plan, self.repo, apply=True)
        self.assertTrue(self.source.exists())
        self.assertFalse(self.target.exists())

    def test_existing_target_is_rejected_without_touching_source(self):
        self.target.write_bytes(b"occupied")
        with self.assertRaisesRegex(compact.PlanError, "already exists"):
            compact.execute_plan(self.plan(), self.repo, apply=True)
        self.assertEqual(self.target.read_bytes(), b"occupied")
        self.assertTrue(self.source.exists())

    def test_invalid_member_path_and_wrong_member_hash_are_rejected(self):
        for bad in (
            [{"path": "../escape", "sha256": digest(b"x")}],
            [{"path": "sessions/s1/trace.jsonl", "sha256": "0" * 64}],
            [{"path": "sessions/s1/trace.jsonl", "sha256": digest(self.members["sessions/s1/trace.jsonl"])},
             {"path": "sessions/s1/trace.jsonl", "sha256": digest(self.members["sessions/s1/trace.jsonl"])}],
        ):
            with self.subTest(bad=bad), self.assertRaises(compact.PlanError):
                compact.execute_plan(self.plan(members=bad), self.repo, apply=True)
            self.assertTrue(self.source.exists())
            self.assertFalse(self.target.exists())

    def test_restricted_paths_and_symlinks_are_rejected(self):
        for relative in (
            "beta-c45-live-20261025/diagnostics.zip",
            "candidate-c4-live-20261024/diagnostics.zip",
            "outside/diagnostics.zip",
            "candidate-c3-test/../outside.zip",
        ):
            with self.subTest(relative=relative), self.assertRaises(compact.PlanError):
                compact.execute_plan(self.plan(sourceZip=relative), self.repo, apply=False)

    def test_apply_preserves_exact_selected_member_bytes_and_records_manifest(self):
        source_hash = compact.sha256_file(self.source)
        compact.execute_plan(self.plan(), self.repo, apply=True)
        self.assertFalse(self.source.exists())
        self.assertTrue(self.target.is_file())
        self.assertTrue(self.manifest.is_file())
        with zipfile.ZipFile(self.target) as archive:
            self.assertEqual(set(archive.namelist()), {
                "sessions/s1/trace.jsonl", "capture-probes/p1/probe.json"
            })
            for name in archive.namelist():
                self.assertEqual(archive.read(name), self.members[name])
        record = json.loads(self.manifest.read_text(encoding="utf-8"))
        self.assertEqual(record["sourceSha256"], source_hash)
        self.assertEqual(record["targetSha256"], compact.sha256_file(self.target))
        self.assertEqual({item["sha256"] for item in record["members"]},
                         {digest(self.members[name]) for name in ("sessions/s1/trace.jsonl", "capture-probes/p1/probe.json")})

    def test_duplicate_requires_same_hash_canonical_and_writes_manifest_first(self):
        canonical = self.allowed / "canonical.zip"
        canonical.write_bytes(self.source.read_bytes())
        duplicate_manifest = self.allowed / "duplicate.manifest.json"
        plan = {"version": 1, "operations": [{
            "operation": "duplicate",
            "evidenceClass": "non_game",
            "sourceZip": "candidate-c3-test/diagnostics.zip",
            "expectedSourceSha256": compact.sha256_file(self.source),
            "retainCanonicalFile": "candidate-c3-test/canonical.zip",
            "manifestPath": "candidate-c3-test/duplicate.manifest.json",
        }]}
        compact.execute_plan(plan, self.repo, apply=True)
        self.assertTrue(canonical.exists())
        self.assertFalse(self.source.exists())
        record = json.loads(duplicate_manifest.read_text(encoding="utf-8"))
        self.assertEqual(record["canonicalSha256"], compact.sha256_file(canonical))

    def test_duplicate_with_mismatched_canonical_is_rejected(self):
        canonical = self.allowed / "canonical.zip"
        with zipfile.ZipFile(canonical, "w") as archive:
            archive.writestr("different.bin", b"different")
        plan = {"version": 1, "operations": [{
            "operation": "duplicate",
            "evidenceClass": "non_game",
            "sourceZip": "candidate-c3-test/diagnostics.zip",
            "expectedSourceSha256": compact.sha256_file(self.source),
            "retainCanonicalFile": "candidate-c3-test/canonical.zip",
            "manifestPath": "candidate-c3-test/duplicate.manifest.json",
        }]}
        with self.assertRaisesRegex(compact.PlanError, "same SHA-256"):
            compact.execute_plan(plan, self.repo, apply=True)
        self.assertTrue(self.source.exists())
        self.assertFalse((self.allowed / "duplicate.manifest.json").exists())

    def test_a_canonical_copy_may_not_be_another_planned_removal(self):
        canonical = self.allowed / "canonical.zip"
        canonical.write_bytes(self.source.read_bytes())
        def op(source, retained, manifest):
            return {"operation": "duplicate", "evidenceClass": "non_game",
                    "sourceZip": "candidate-c3-test/"+source,
                    "expectedSourceSha256": compact.sha256_file(self.source),
                    "retainCanonicalFile": "candidate-c3-test/"+retained,
                    "manifestPath": "candidate-c3-test/"+manifest}
        plan={"version":1,"operations":[op("diagnostics.zip","canonical.zip","one.json"),
                                           op("canonical.zip","diagnostics.zip","two.json")]}
        with self.assertRaisesRegex(compact.PlanError,"canonical ZIP"):
            compact.execute_plan(plan,self.repo,apply=True)
        self.assertTrue(self.source.exists())
        self.assertTrue(canonical.exists())

    def test_windows_drive_member_names_are_not_safe_zip_paths(self):
        with self.assertRaises(compact.PlanError):
            compact._zip_member_path("C:/escape.bin")


if __name__ == "__main__":
    unittest.main()
