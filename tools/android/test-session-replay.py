"""Local schema/provenance checks for prepare-session-replay; no device or Gradle."""
import importlib.util
import contextlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("session_replay", Path(__file__).with_name("prepare-session-replay.py"))
replay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(replay)
TEMP_ROOT = Path(__file__).resolve().parents[2] / "tmp" / "python-tests"
TEMP_ROOT.mkdir(parents=True, exist_ok=True)


class SessionReplayTests(unittest.TestCase):
    def torn_archive(self, folder, tail, *, broken_part="trace.jsonl", after=b""):
        archive = Path(folder) / "torn.zip"
        prefix = "sessions/session-1/"
        header = json.dumps({"type": "session", "sessionId": "session-1"}).encode() + b"\n"
        frame = json.dumps(self.frame()).encode() + b"\n"
        # Include multibyte text before the torn line to test byte, not character,
        # provenance offsets. The earlier frame must still replay independently.
        first = header + json.dumps({"type": "note", "text": "来源"}, ensure_ascii=False).encode() + b"\n" + frame
        with zipfile.ZipFile(archive, "w") as zipped:
            if broken_part != "trace.jsonl":
                zipped.writestr(prefix + broken_part, first + tail + after)
                zipped.writestr(prefix + "trace.jsonl", frame)
            else:
                zipped.writestr(prefix + "trace.previous.jsonl", header + frame)
                zipped.writestr(prefix + broken_part, first + tail + after)
        return archive, first

    def test_torn_final_record_requires_explicit_opt_in_and_keeps_raw_provenance(self):
        tail = b'{"type":"frame","sessionId":"session-1","elapsedMs":9999,"scene":"RESULT","adjusting":false'
        with self.temporary_directory() as folder:
            archive, first = self.torn_archive(folder, tail)
            original_archive = archive.read_bytes()
            strict_out = Path(folder) / "strict"
            with self.assertRaisesRegex(ValueError, "Malformed JSON"):
                replay.prepare(archive, ["session-1"], strict_out, strict_out / "manifest.json")
            self.assertFalse((strict_out / "manifest.json").exists())
            output = Path(folder) / "allowed"
            result = replay.prepare(archive, ["session-1"], output, output / "manifest.json",
                                    allow_truncated_final_record=True)
            source = result["sources"][0]
            self.assertTrue(result["allowTruncatedFinalRecord"])
            self.assertEqual(2, source["frameCount"])
            self.assertTrue(all(row.startswith("1234;") for row in (output / "session-1.tape").read_text().splitlines()))
            member = "sessions/session-1/trace.jsonl"
            self.assertEqual([{
                "member": member, "lineNumber": 4, "byteOffset": len(first),
                "byteLength": len(tail), "sha256": replay.sha256_bytes(tail),
                "reason": "truncated_final_frame_record_at_eof",
            }], source["omittedTruncatedFinalRecords"])
            with zipfile.ZipFile(archive) as zipped:
                self.assertEqual(replay.sha256_bytes(b"".join(zipped.read(part["member"]) for part in source["traceParts"])),
                                 source["traceSha256"])
                for part in source["traceParts"]:
                    self.assertEqual(replay.sha256_bytes(zipped.read(part["member"])), part["sha256"])
            self.assertEqual(original_archive, archive.read_bytes())
            self.assertEqual(result, json.loads((output / "manifest.json").read_text(encoding="utf-8")))

    def test_opt_in_rejects_damage_outside_final_unterminated_current_part(self):
        tail = b'{"type":"frame","adjusting":false'
        cases = [("rotated", "trace.previous.jsonl", b""),
                 ("older_rotated", "trace.previous.2.jsonl", b""),
                 ("newline", "trace.jsonl", b"\n"),
                 ("crlf", "trace.jsonl", b"\r\n"),
                 ("cr", "trace.jsonl", b"\r"),
                 ("middle", "trace.jsonl", b"\n" + json.dumps(self.frame()).encode())]
        for label, part, after in cases:
            with self.subTest(label=label), self.temporary_directory() as folder:
                archive, _ = self.torn_archive(folder, tail, broken_part=part, after=after)
                output = Path(folder) / "out"
                with self.assertRaisesRegex(ValueError, "Malformed JSON"):
                    replay.prepare(archive, ["session-1"], output, output / "manifest.json",
                                   allow_truncated_final_record=True)
                self.assertFalse((output / "manifest.json").exists())

    def test_opt_in_rejects_arbitrary_malformed_or_nonframe_eof_records(self):
        cases = [b'{"type":"frame","x":false,}', b'{"type":"frame","x":truX',
                 b'{"type":"frame","x":1 garbage', b'{"type":"frame","x":01',
                 b'{"type":"frame","x":"bad\\q', b'{"type":"frame","x":"bad\\u0Z',
                 b'{"type":"frame","x":"bad\x01', b'{"type":"frame","x":NaN,',
                 b'{"type":"frame","x":[1,]', b'{"type":"frame","x":1.e',
                 b'{"type":"frame","x":1.2.', b'{"type":"frame","x":1e00.',
                 b'{"type":"session","sessionId":"session-1"', b'{"type":"fra',
                 b'[ {"type":"frame"', b'{"type":"frame","type":"frame",',
                 b'{"type":"frame","sessionId":"other",',
                 b'{"type":"frame","sessionId":"session-',
                 b'{"type":"frame","sessionId":{},', b'{"type":"frame","sessionId":1,',
                 b'{"type":"frame","sessionId":false,', b'{"type":"frame","x":"\xff',
                 b'{"type":"frame"} trailing']
        for tail in cases:
            with self.subTest(tail=tail), self.temporary_directory() as folder:
                archive, _ = self.torn_archive(folder, tail)
                output = Path(folder) / "out"
                with self.assertRaisesRegex(ValueError, "Malformed JSON"):
                    replay.prepare(archive, ["session-1"], output, output / "manifest.json",
                                   allow_truncated_final_record=True)

    def test_prefix_validator_recognizes_only_extendable_frame_json(self):
        for suffix in (b'', b',', b',"key', b',"key":', b',"key":false', b',"key":tru',
                       b',"key":[', b',"key":[{"nested":"value', b',"key":"\\u01',
                       b',"key":"escape\\', b',"key":-', b',"key":1.',
                       b',"key":1e+', b',"key":1.2e', b',"key":null'):
            with self.subTest(suffix=suffix):
                self.assertTrue(replay.truncated_frame_prefix(b'{"type":"frame"' + suffix, "session-1"))
        self.assertFalse(replay.truncated_frame_prefix(b'{"type":"frame"}', "session-1"))

    def test_valid_nested_json_can_be_torn_at_every_position_after_frame_type(self):
        start = b'{"type":"frame",'
        for payload in ([1, -1, 0.25, 1e20, True, False, None, {"nested": ["escape\\\"", "\u4e8c"]}],
                        "control\n\t\u0001", {"nested": {"empty": [], "object": {}}}):
            complete = start + b'"payload":' + json.dumps(payload, ensure_ascii=True).encode() + b'}'
            for cut in range(len(start), len(complete)):
                with self.subTest(payload=payload, cut=cut):
                    self.assertTrue(replay.truncated_frame_prefix(complete[:cut], "session-1"))
            self.assertFalse(replay.truncated_frame_prefix(complete, "session-1"))

    def test_complete_unterminated_frame_is_kept_and_legacy_parse_api_stays_four_values(self):
        with self.temporary_directory() as folder:
            tail = json.dumps(self.frame(elapsedMs=9999)).encode()
            archive, _ = self.torn_archive(folder, tail)
            with zipfile.ZipFile(archive) as zipped:
                parsed = replay.parse_trace(zipped, "sessions/session-1/trace.jsonl", "session-1")
                self.assertEqual(4, len(parsed))
                self.assertEqual(2, len(parsed[0].splitlines()))
            for allowed in (False, True):
                output = Path(folder) / str(allowed)
                result = replay.prepare(archive, ["session-1"], output, output / "manifest.json",
                                        allow_truncated_final_record=allowed)
                self.assertIs(allowed, result["allowTruncatedFinalRecord"])
                self.assertEqual([], result["sources"][0]["omittedTruncatedFinalRecords"])
                self.assertEqual(3, result["sources"][0]["frameCount"])

    def test_opt_in_does_not_hide_missing_header_or_absence_of_complete_frames(self):
        for valid_records in ([], [{"type": "session", "sessionId": "session-1"}], [self.frame()]):
            with self.subTest(valid_records=valid_records), self.temporary_directory() as folder:
                archive = Path(folder) / "empty.zip"
                with zipfile.ZipFile(archive, "w") as zipped:
                    zipped.writestr("sessions/session-1/trace.jsonl",
                                    b"".join(json.dumps(record).encode()+b"\n" for record in valid_records) +
                                    b'{"type":"frame","adjusting":false')
                output = Path(folder) / "out"
                with self.assertRaisesRegex(ValueError, "no matching session header|no frames"):
                    replay.prepare(archive, ["session-1"], output, output / "manifest.json",
                                   allow_truncated_final_record=True)

    def test_opt_in_does_not_hide_complete_frame_validation_errors(self):
        for changes in ({"elapsedMs": -1}, {"glyphs": [["invalid"]]}, {"passing": ["invalid"]},
                        {"sessionId": "other"}):
            with self.subTest(changes=changes), self.temporary_directory() as folder:
                archive, _ = self.torn_archive(folder, json.dumps(self.frame(**changes)).encode())
                output = Path(folder) / "out"
                with self.assertRaises(ValueError):
                    replay.prepare(archive, ["session-1"], output, output / "manifest.json",
                                   allow_truncated_final_record=True)

    def test_cli_opt_in_is_explicit_and_records_flag(self):
        with self.temporary_directory() as folder:
            archive, _ = self.torn_archive(folder, b'{"type":"frame","adjusting":false')
            for allowed in (False, True):
                output = Path(folder) / str(allowed)
                argv = ["prepare-session-replay.py", "--archive", str(archive), "--session", "session-1",
                        "--output", str(output), "--manifest", str(output / "manifest.json")]
                if allowed:
                    argv.append("--allow-truncated-final-record")
                stdout, stderr = io.StringIO(), io.StringIO()
                with patch.object(sys, "argv", argv), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                    status = replay.main()
                self.assertEqual(0 if allowed else 1, status)
                if allowed:
                    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
                    self.assertTrue(manifest["allowTruncatedFinalRecord"])
                else:
                    self.assertIn("Malformed JSON", stderr.getvalue())

    def test_finish_replay_copies_original_positioned_source_not_rank_inference(self):
        frame = self.frame(scene="TEAMMATE", elapsedMs=2000, sceneSourceAgeMs=300,
                           sceneSourceMasks=[], overlayMasks=[], sceneSourceHints=[
                               {"text": "二游", "box": [.0763, .2158, .1189, .25]},
                               {"text": "三游", "box": [.88, .2, .92, .25]}])
        columns = replay.frame_to_tape_row(frame)[0].split(";")
        self.assertEqual(17, len(columns))
        self.assertEqual("二游,1700,0.0763,0.2158,0.1189,0.25|三游,1700,0.88,0.2,0.92,0.25", columns[16])
        for change in ({"sceneSourceAgeMs": 1001}, {"sceneSourceAgeMs": -1},
                       {"sceneSourceMasks": None}, {"adjusting": True}):
            self.assertEqual("?", replay.encode_early_finish_hints(frame | change))
        result_frame=frame | {"scene":"RESULT","sceneSourceHints":[
            {"text":"末游","box":[.4716,.6733,.5276,.7383]}]}
        self.assertEqual("末游,1700,0.4716,0.6733,0.5276,0.7383",
                         replay.encode_early_finish_hints(result_frame))
        self.assertEqual("三游,1700,0.88,0.2,0.92,0.25",
                         replay.encode_early_finish_hints(frame | {"overlayMasks": [[.07, .2, .13, .26]]}))
        self.assertEqual("", replay.encode_early_finish_hints(frame | {
            "overlayMasks": [[.07, .2, .13, .26], [.87, .19, .93, .26]]}))

    def test_replay_preserves_each_qualified_finish_rank_at_its_ocr_source_time(self):
        frame=self.frame(scene="RESULT",elapsedMs=2000,sceneSourceAgeMs=300,
            sceneSourceMasks=[],overlayMasks=[],sceneSourceHints=[
                {"text":"头游","box":[.8,.2,.85,.25]},
                {"text":"二游","box":[.41,.07,.46,.11]},
                {"text":"三游","box":[.4,.67,.46,.74]},
                {"text":"末游","box":[.47,.67,.53,.74]}])
        columns=replay.frame_to_tape_row(frame)[0].split(";")
        self.assertEqual(17,len(columns))
        self.assertEqual("头游,1700,0.8,0.2,0.85,0.25|二游,1700,0.41,0.07,0.46,0.11|三游,1700,0.4,0.67,0.46,0.74|末游,1700,0.47,0.67,0.53,0.74",
                         columns[16])

    def test_rotated_sources_replay_oldest_first_and_bind_every_part(self):
        with self.temporary_directory() as folder:
            archive=Path(folder)/"parts.zip"
            prefix="sessions/session-1/"
            header={"type":"session","sessionId":"session-1"}
            with zipfile.ZipFile(archive,"w") as zipped:
                for name,records in [("trace.jsonl",[self.frame(elapsedMs=3000)]),
                                     ("trace.previous.2.jsonl",[header,self.frame(elapsedMs=1000)]),
                                     ("trace.previous.jsonl",[self.frame(elapsedMs=2000)])]:
                    zipped.writestr(prefix+name,"".join(json.dumps(j)+"\n" for j in records))
            out=Path(folder)/"out";result=replay.prepare(archive,["session-1"],out,out/"manifest.json")
            self.assertEqual(["1000","2000","3000"],[row.split(";")[0] for row in (out/"session-1.tape").read_text().splitlines()])
            parts=result["sources"][0]["traceParts"]
            self.assertEqual([prefix+name for name in ["trace.previous.2.jsonl","trace.previous.jsonl","trace.jsonl"]],
                             [part["member"] for part in parts])
            with zipfile.ZipFile(archive) as zipped:
                self.assertTrue(all(part["sha256"]==replay.sha256_bytes(zipped.read(part["member"])) for part in parts))

    def test_pass_sources_are_optional_and_never_inferred_from_legacy_passing_flags(self):
        row,_=replay.frame_to_tape_row(self.frame())
        self.assertEqual(14,len(row.split(";")))
        frame=self.frame(observedPasses={"OPPOSITE":1200,"NEXT":1235,"SELF":1200,"PREVIOUS":100})
        row,_=replay.frame_to_tape_row(frame)
        self.assertEqual("",row.split(";")[14])
        self.assertEqual("OPPOSITE,1200",row.split(";")[15])

    def test_explicit_physical_single_keeps_its_own_source_and_no_legacy_inference(self):
        frame=self.frame(groups={"OPPOSITE":["A:BLACK"]},regionObserved={"OPPOSITE":True},
            singleCardIdentities={"OPPOSITE":{"card":"A:BLACK","suit":"CLUB","similarity":.86,"margin":.15,"sourceTimeMs":1234}})
        row,_=replay.frame_to_tape_row(frame)
        self.assertEqual("OPPOSITE,A,CLUB,0.86,0.15,1234",row.split(";")[14])
        frame["singleCardIdentities"]["OPPOSITE"]["sourceTimeMs"]=1235
        self.assertEqual("",replay.encode_single_identities(frame))
        frame["singleCardIdentities"]["OPPOSITE"]["sourceTimeMs"]=1234
        frame["regionObserved"]["OPPOSITE"]=False
        self.assertEqual("",replay.encode_single_identities(frame))

    def temporary_directory(self):
        return tempfile.TemporaryDirectory(dir=TEMP_ROOT)

    def frame(self, **changes):
        return {
            "type": "frame", "sessionId": "session-1", "elapsedMs": 1234,
            "scene": "TABLE", "selfFinished": False,
            "glyphs": [["FOUR:RED", .9, .1, .2, .3, .4]],
            "passing": ["PREVIOUS"],
            "overlayMasks": [[.1, .2, .3, .4]],
            "emptyEvidence": {
                "SELF": {"observed": True, "reason": "table_surface_visible"},
                "PREVIOUS": {"observed": True, "reason": "table_surface_visible"},
                "OPPOSITE": {"observed": False, "reason": "overlay_occlusion"},
            },
            "remainingHandCounts": {
                "PREVIOUS": {"count": 9, "sourceTimeMs": 1200},
                "NEXT": {"count": 27, "sourceTimeMs": 1210},
            },
            **changes,
        }

    def make_archive(self, folder, frames, include_probe=True):
        folder = Path(folder)
        archive = folder / "diagnostics.zip"
        trace = [{"type": "session", "sessionId": "session-1"}, *frames]
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("sessions/session-1/trace.jsonl", "".join(json.dumps(row) + "\n" for row in trace))
            zipped.writestr("diagnostic/source.jpg", b"archive-source-image")
            if include_probe:
                zipped.writestr("capture-probes/probe-a/probe.json", json.dumps({"imageTimestampNs": 88}))
                zipped.writestr("capture-probes/probe-a/legacy.png", b"source-image")
                zipped.writestr("capture-probes/probe-a/converted.png", b"converted-image")
        return archive

    def prepare(self, folder, frames, include_probe=True):
        archive = self.make_archive(folder, frames, include_probe)
        output = Path(folder) / "out"
        result = replay.prepare(archive, ["session-1"], output, output / "manifest.json")
        return archive, output, result

    def test_preserves_trace_semantics_masks_counts_and_provenance(self):
        with self.temporary_directory() as folder:
            frame = self.frame(imageTimestampNs=88)
            archive, output, result = self.prepare(folder, [frame])
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual(14, len(row))
            self.assertEqual(["1234", "TABLE", "false", "true,true,false,false", "true", "false"], row[:6])
            self.assertEqual("FOUR,RED,0.9,0.1,0.2,0.3,0.4", row[6])
            self.assertEqual("PREVIOUS", row[7])
            self.assertEqual("false", row[8])
            self.assertEqual("false", row[9])
            self.assertEqual("0.09990,0.19990,0.30010,0.40010", row[10])
            self.assertEqual("?", row[11])
            self.assertEqual("PREVIOUS,9,1200|NEXT,27,1210", row[12])
            self.assertEqual("table_surface_visible,table_surface_visible,overlay_occlusion,?", row[13])
            source = result["sources"][0]
            self.assertEqual(2, result["schemaVersion"])
            self.assertEqual(replay.sha256_file(archive), result["archiveSha256"])
            self.assertEqual(1, source["frameCount"])
            self.assertEqual(replay.sha256_bytes((output / "session-1.tape").read_bytes()), source["tapeSha256"])
            self.assertEqual(64, len(source["traceSha256"]))
            self.assertEqual(2, len(source["sourceImageSha256s"]))
            archive_image = next(image for image in result["archiveImages"]
                                 if image["member"] == "diagnostic/source.jpg")
            self.assertEqual(replay.sha256_bytes(b"archive-source-image"), archive_image["sha256"])

    def test_opening_proof_requires_its_source_timestamp_and_counts_drop_bad_sources(self):
        with self.temporary_directory() as folder:
            frame = self.frame(openingChoiceSourceTimeMs=1201, dealingChoicesObserved=True,
                               remainingHandCounts={"PREVIOUS": {"count": 6}})
            _, output, _ = self.prepare(folder, [frame], include_probe=False)
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual("1201", row[11])
            self.assertEqual("", row[12])
            self.assertEqual("table_surface_visible,table_surface_visible,overlay_occlusion,?", row[13])
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(1, manifest["sources"][0]["omittedMalformedCountEntries"])
            self.assertEqual([], manifest["sources"][0]["sourceImageSha256s"])

    def test_reason_proves_capture_gap_and_adjustment_without_glyph_inference(self):
        with self.temporary_directory() as folder:
            frame = self.frame(reason="adjustment_occlusion", glyphs=None, overlayMasks=None,
                               emptyEvidence=None, remainingHandCounts=None)
            _, output, _ = self.prepare(folder, [frame], include_probe=False)
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual("UNKNOWN", row[1])
            self.assertEqual("false,false,false,false", row[3])
            self.assertEqual("false", row[4])
            self.assertEqual("true", row[5])
            self.assertEqual("", row[6])
            self.assertEqual("true", row[8])
            self.assertEqual("?", row[10])
            self.assertEqual("?", row[11])
            self.assertEqual("", row[12])

    def test_unconfirmed_adjusting_and_bad_opening_timestamp_stay_false_or_unknown(self):
        with self.temporary_directory() as folder:
            frame = self.frame(overlayMasks=[[.2, .2, .3, .3]], openingChoiceSourceTimeMs=True)
            _, output, _ = self.prepare(folder, [frame], include_probe=False)
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual("false", row[5])
            self.assertEqual("?", row[11])

    def test_missing_glyphs_and_source_reason_force_unknown_capture_gap(self):
        with self.temporary_directory() as folder:
            frame = self.frame(glyphs=None, captureGap=False, reason="portrait_non_game")
            _, output, _ = self.prepare(folder, [frame], include_probe=False)
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual("UNKNOWN", row[1])
            self.assertEqual("true", row[8])

    def test_joker_color_keys_and_zero_detections_are_preserved(self):
        with self.temporary_directory() as folder:
            frame = self.frame(scene="LOBBY", glyphs=[
                ["BIG", .99, .1, .2, .2, .3], ["SMALL", .98, .3, .2, .4, .3]],
                emptyEvidence={"SELF": {"observed": False, "reason": "unrecognized;reason"}})
            _, output, _ = self.prepare(folder, [frame], include_probe=False)
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual("BIG,NONE,0.99,0.1,0.2,0.2,0.3|SMALL,NONE,0.98,0.3,0.2,0.4,0.3", row[6])
            self.assertEqual("LOBBY", row[1])
            self.assertEqual("false", row[8])
            self.assertEqual("?,?,?,?", row[13])

        with self.temporary_directory() as folder:
            frame = self.frame(scene="RESULT", glyphs=[])
            _, output, _ = self.prepare(folder, [frame], include_probe=False)
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual("RESULT", row[1])
            self.assertEqual("false", row[8])

        with self.temporary_directory() as folder:
            frame = self.frame(glyphs=None, reason=None)
            _, output, _ = self.prepare(folder, [frame], include_probe=False)
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual("UNKNOWN", row[1])
            self.assertEqual("true", row[8])

    def test_missing_or_duplicate_trace_and_unsafe_session_are_rejected(self):
        with self.temporary_directory() as folder:
            archive = self.make_archive(folder, [self.frame()], include_probe=False)
            output = Path(folder) / "out"
            with self.assertRaises(ValueError):
                replay.prepare(archive, ["../escape"], output, output / "manifest.json")
        with self.temporary_directory() as folder:
            archive = Path(folder) / "duplicate.zip"
            trace = json.dumps({"type": "session", "sessionId": "session-1"}) + "\n"
            with zipfile.ZipFile(archive, "w") as zipped:
                zipped.writestr("sessions/session-1/trace.jsonl", trace)
                zipped.writestr("sessions/session-1/trace.jsonl", trace)
            output = Path(folder) / "out"
            with self.assertRaises(ValueError):
                replay.prepare(archive, ["session-1"], output, output / "manifest.json")


if __name__ == "__main__":
    unittest.main()
