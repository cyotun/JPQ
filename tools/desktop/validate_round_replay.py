"""Compare fresh desktop/core replay to separately supplied manual card labels."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path


def validate(actual: dict, expected: dict) -> dict:
    problems = []
    required = {"initial", "events", "remaining", "frames", "failures", "lastKnownPhase"}
    if not required.issubset(actual) or not isinstance(actual.get("failures"), list):
        return {"schemaVersion": 1, "passed": False, "problems": ["incomplete_core_report"]}
    if Counter(actual["initial"]) != Counter(expected["initial"]):
        problems.append("initial_hand_differs")
    selected_seats = set(expected["seats"])
    plays = [p for p in actual["events"] if p["seat"] in selected_seats]
    truth = expected["plays"]
    if len(plays) != len(truth):
        problems.append("play_count_differs")
    differences = []
    for index, (found, wanted) in enumerate(zip(plays, truth)):
        if found["seat"] != wanted["seat"] or Counter(found["cards"]) != Counter(wanted["cards"]):
            differences.append(index + 1)
    if differences:
        problems.append("play_order_seat_or_cards_differ")
    if "ownPlays" in expected:
        own = [p["cards"] for p in actual["events"] if p["seat"] == "SELF"]
        if len(own) != len(expected["ownPlays"]) or any(
                Counter(a) != Counter(b) for a, b in zip(own, expected["ownPlays"])):
            problems.append("own_play_cards_or_order_differ")
    if actual["remaining"] != expected["remaining"]:
        problems.append("final_remaining_differs")
    if actual["frames"] != expected["frames"]:
        problems.append("frame_coverage_differs")
    if actual.get("failures"):
        problems.append("core_reported_failures")
    if actual.get("lastKnownPhase") != "FINISHED":
        problems.append("round_not_finished")
    return {"schemaVersion": 1, "passed": not problems, "problems": problems,
            "expectedPlays": len(truth), "actualPlays": len(plays), "differentPlayIndices": differences,
            "frames": actual["frames"], "expectedRemaining": expected["remaining"],
            "actualRemaining": actual["remaining"],
            "scope": "fresh_desktop_model_and_shared_core_against_supplied_manual_card_labels",
            "androidCaptureTested": False, "androidOcrTested": False,
            "phoneAcceptancePassed": False}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--actual", type=Path, required=True)
    parser.add_argument("--expected", type=Path, required=True)
    parser.add_argument("--inference-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    actual = json.loads(args.actual.read_text(encoding="utf-8-sig"))
    expected = json.loads(args.expected.read_text(encoding="utf-8-sig"))
    inference = json.loads(args.inference_report.read_text(encoding="utf-8-sig"))
    manifest_path = args.inference_report.parent / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    # Keep semantic labels and model execution tied to the same reviewed video.
    if (inference.get("status") != "inference_complete" or inference.get("freshModelInference") is not True
            or inference.get("kind") != "video" or inference.get("frames") != expected["frames"]
            or inference.get("model", {}).get("modelSha") != "87493b13b285027b7ee0f727bc9ca5b9a2aec8c7e0e730e43a79417121f3a90c"
            or inference["manifestSha256"] != hashlib.sha256(manifest_path.read_bytes()).hexdigest()
            or manifest["source"]["sha256"] != expected["sourceVideoSha256"]):
        raise ValueError("Upstream inference or source provenance differs")
    result = validate(actual, expected)
    result["inferenceReportSha256"] = hashlib.sha256(args.inference_report.read_bytes()).hexdigest()
    result["actualSha256"] = hashlib.sha256(args.actual.read_bytes()).hexdigest()
    result["expectedSha256"] = hashlib.sha256(args.expected.read_bytes()).hexdigest()
    with args.output.open("x", encoding="utf-8") as sink:
        json.dump(result, sink, indent=2)
        sink.write("\n")
    print(json.dumps(result))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
