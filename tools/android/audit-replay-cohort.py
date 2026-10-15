"""Audit one frozen replay cohort. Never equate a result screen with full counting.

Inputs and reports remain private. The output contains only case identifiers and
coverage, with explicit exclusions. It is a replay gate, not image-level accuracy.
"""
import argparse
import hashlib
import json
from pathlib import Path


def verify_exclusion(item, entries):
    """Verify a reviewed evidence bundle's identity; pixel interpretation stays human-reviewed."""
    if item.get("category") not in {"grade_occluded", "screen_occluded"} or not item.get("reason"):
        raise ValueError("Exclusion needs an allowed category and reason")
    reference = item.get("evidence")
    if not isinstance(reference, dict) or not reference.get("path") or not reference.get("sha256"):
        raise ValueError("Exclusion needs a hashed local evidence review")

    def verified_file(ref):
        path = Path(ref["path"])
        if not path.is_absolute() or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != ref["sha256"]:
            raise ValueError("Evidence is missing or its hash changed")
        return path

    review = json.loads(verified_file(reference).read_text(encoding="utf-8-sig"))
    if any(review.get(key) != item.get(key) for key in ("session", "roundId", "category")):
        raise ValueError("Evidence review belongs to another case")
    entry = entries.get(item.get("session"))
    if entry is None or not entry.get("sha256") or review.get("sourceSha256") != entry["sha256"]:
        raise ValueError("Evidence is not tied to the frozen source")
    bounds = review.get("frameRange")
    if (not isinstance(bounds, list) or len(bounds) != 2 or
            any(type(x) is not int for x in bounds) or not 1 <= bounds[0] <= bounds[1] <= entry["frames"]):
        raise ValueError("Evidence needs a valid source frame range")
    if review.get("confirmedOcclusion") is not True or not review.get("reviewedBy") or not review.get("artifacts"):
        raise ValueError("Occlusion needs an explicit review and preserved artifacts")
    for artifact in review["artifacts"]:
        verified_file(artifact)


def audit(inputs, report_dir, exclusions=None, expected_sources=33, minimum_rounds=33):
    exclusions = exclusions or []
    exempt = {}
    for item in exclusions:
        verify_exclusion(item, {entry["session"]: entry for entry in inputs})
        key = (item["session"], item["roundId"])
        if key in exempt:
            raise ValueError("Duplicate exclusion")
        exempt[key] = item
    cases, problems, sources = [], [], []
    seen, seen_rounds = set(), set()
    for entry in sorted(inputs, key=lambda x: x["session"]):
        session = entry["session"]
        if session in seen or Path(session).name != session:
            raise ValueError("Source IDs must be unique filenames")
        seen.add(session)
        file = report_dir / (session + ".json")
        if not file.is_file():
            problems.append({"session": session, "reason": "missing_report"})
            continue
        raw = file.read_bytes()
        report = json.loads(raw)
        sources.append({"session": session, "sha256": hashlib.sha256(raw).hexdigest(),
                        "frames": report["frames"], "rounds": len(report.get("rounds", []))})
        if report["frames"] != entry["frames"]:
            problems.append({"session": session, "reason": "input_frame_count_mismatch"})
        for item in report.get("rounds", []):
            key = (session, item["roundId"])
            if key in seen_rounds:
                raise ValueError("A duplicated round cannot pad the cohort")
            seen_rounds.add(key)
            result = {"session": session, **item}
            if key in exempt:
                result.update(status="excluded", exclusion=exempt.pop(key))
            elif item["confirmedEvents"] == 0:
                result.update(status="no_play_evidence")
            elif item["endedAt"] is None:
                result.update(status="incomplete_source")
            elif (item["lastPhase"] == "FINISHED" and item.get("countsVerified") is True
                  and item.get("completionObserved") is True):
                result.update(status="passed_recorded_evidence")
            else:
                result.update(status="failed")
            cases.append(result)
    if len(inputs) != expected_sources:
        problems.append({"reason": "wrong_source_count", "expected": expected_sources, "actual": len(inputs)})
    if exempt:
        raise ValueError("Exclusion refers to a nonexistent round")
    streak = longest = passed = 0
    for case in cases:
        if case["status"] == "passed_recorded_evidence":
            passed += 1
            streak += 1
            longest = max(longest, streak)
        elif case["status"] not in {"excluded", "no_play_evidence"}:
            streak = 0
    failures = sum(x["status"] in {"failed", "incomplete_source"} for x in cases)
    return {"scope": "recorded_model_scene_and_ledger_continuity_not_independent_card_truth",
            "sourceCount": len(sources), "sourceFrames": sum(x["frames"] for x in sources),
            "passedRounds": passed, "failedOrIncompleteRounds": failures,
            "excludedRounds": sum(x["status"] == "excluded" for x in cases),
            "noPlayEvidence": sum(x["status"] == "no_play_evidence" for x in cases),
            "longestConsecutivePassingRounds": longest,
            "gatePassed": not problems and failures == 0 and longest >= minimum_rounds,
            "problems": problems, "sources": sources, "cases": cases}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--reports", type=Path, required=True)
    parser.add_argument("--exclusions", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = audit(json.loads(args.inputs.read_text(encoding="utf-8-sig")), args.reports,
                   json.loads(args.exclusions.read_text(encoding="utf-8-sig")) if args.exclusions else [])
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k not in {"cases", "sources"}}, ensure_ascii=True))
    raise SystemExit(0 if result["gatePassed"] else 1)


if __name__ == "__main__":
    main()
