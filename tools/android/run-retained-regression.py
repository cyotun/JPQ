"""Replay the locally retained, hash-locked sources; never downloads private inputs."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import argparse
import hashlib
import json
import subprocess


def normalized_events(events):
    return {rid: [event | {"cards": sorted(event["cards"])} for event in items]
            for rid, items in events.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", default="artifacts/regression-minimal-0.2.27")
    parser.add_argument("--output", required=True, help="New output directory; existing runs are never overwritten")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    source = (root / args.input).resolve()
    output = (root / args.output).resolve()
    manifest = json.loads((source / "source-manifest.json").read_text(encoding="utf-8-sig"))
    stdlib = list((root / ".gradle-home/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.3.21").glob("*/*.jar"))
    if len(stdlib) != 1:
        raise RuntimeError("Build :core:test first to prepare compiled classes and the pinned Kotlin runtime")
    classpath = ";".join(map(str, [root / "core/build/classes/kotlin/test",
                                  root / "core/build/classes/kotlin/main", stdlib[0]]))
    output.mkdir(parents=True, exist_ok=False)

    def replay(entry):
        tape = source / entry["tape"]
        assert hashlib.sha256(tape.read_bytes()).hexdigest() == entry["sourceSha256"], "Source hash mismatch"
        expected = json.loads((source / entry["expected"]).read_text(encoding="utf-8-sig"))
        result_path = output / (entry["id"] + ".json")
        process = subprocess.run([str(root / ".android-tools/jdk-17.0.20.1+1/bin/java.exe"), "-cp", classpath,
                                  "com.cyotun.jpq.core.PhoneLoggedFrameReplayKt", str(tape), str(result_path)],
                                 cwd=root, text=True, capture_output=True, encoding="utf-8", errors="replace")
        (output / (entry["id"] + ".log")).write_text(process.stdout + process.stderr, encoding="utf-8")
        process.check_returncode()
        actual = json.loads(result_path.read_text(encoding="utf-8-sig"))
        assert actual["failures"] == 0, entry["id"] + ": replay failure"
        for field in ("frames", "finishedRounds", "verifiedFinishedRounds", "unresolvedRounds"):
            assert actual[field] == expected[field], entry["id"] + ": " + field
        assert normalized_events(actual["eventsByRound"]) == normalized_events(expected["eventsByRound"]), entry["id"] + ": event mismatch"
        assert actual["teammatePoolHistory"] == expected["teammatePoolHistory"], entry["id"] + ": teammate pool mismatch"
        # Preserve source gaps as gaps; never accept an incomplete round as verified.
        for observed, original in zip(actual["rounds"], expected["rounds"], strict=True):
            for field in ("countsVerified", "self", "previous", "opposite", "next", "lastPhase"):
                assert observed[field] == original[field], entry["id"] + ": final ledger " + field
            if entry.get("unresolvedExpected", 0) == 0:
                assert observed["recoveringFrames"] == 0
        return {"id": entry["id"], "frames": actual["frames"], "finishedRounds": actual["verifiedFinishedRounds"],
                "unresolvedRounds": actual["unresolvedRounds"],
                "events": sum(map(len, actual["eventsByRound"].values())),
                "exactSemanticEventsAndFinalPools": True}

    with ThreadPoolExecutor(max_workers=3) as executor:
        results = list(executor.map(replay, manifest["entries"]))
    normal = [item for item in results if item["unresolvedRounds"] == 0]
    summary = {"scope": "Fixed recorded observations, not new image recognition or live validation",
               "normalRounds": sum(x["finishedRounds"] for x in normal),
               "normalFrames": sum(x["frames"] for x in normal),
               "normalEvents": sum(x["events"] for x in normal),
               "interruptedSourcesAreNotAcceptedRounds": True, "sources": results}
    assert summary["normalRounds"] == manifest["normalRounds"]
    assert summary["normalFrames"] == manifest["normalFrames"]
    assert summary["normalEvents"] == manifest["normalEvents"]
    (output / "verification.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
