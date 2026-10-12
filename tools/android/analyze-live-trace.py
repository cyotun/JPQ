"""Summarize local JPQ JSONL/logcat evidence; this is not an accuracy verdict."""
from __future__ import annotations

import argparse
import json
import math
from collections import Counter, defaultdict
from pathlib import Path
from typing import Iterable


def records(lines: Iterable[str]) -> tuple[list[dict], int]:
    result, malformed = [], 0
    for line in lines:
        text = line.strip()
        if not text:
            continue
        if not text.startswith("{"):
            # Restrict extraction to our structured tag; never parse other logs.
            if "JPQTrace" not in text or ":" not in text:
                continue
            start = text.find("{", text.find("JPQTrace"))
            if start < 0:
                continue
            text = text[start:]
        try:
            record = json.loads(text)
        except json.JSONDecodeError:
            malformed += 1
            continue
        if isinstance(record, dict) and "sessionId" in record and "type" in record:
            result.append(record)
    return result, malformed


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    return sorted(values)[max(0, math.ceil(len(values) * p) - 1)]


def summarize(rows: list[dict], malformed: int = 0) -> dict:
    sessions = defaultdict(list)
    for row in rows:
        sessions[str(row["sessionId"])].append(row)
    output = []
    for session, data in sorted(sessions.items()):
        # Merging logcat and full private traces produces intentional copies.
        unique = list({json.dumps(r, sort_keys=True, ensure_ascii=False): r for r in data}.values())
        frames = [r for r in unique if r.get("type") == "frame"]
        ledgers = sorted((r for r in unique if r.get("type") == "ledger"), key=lambda r: r.get("elapsedMs", 0))
        ordered_frames = sorted(frames, key=lambda r: r.get("elapsedMs", 0))
        times = [r["elapsedMs"] for r in ordered_frames if isinstance(r.get("elapsedMs"), (int, float))]
        gaps = [b - a for a, b in zip(times, times[1:])]
        events = {}
        conflicts = []
        for ledger in ledgers:
            # Current traces carry every event changed by a ledger snapshot.
            # Empty changes is meaningful; do not fall back to a stale legacy
            # event field if both formats happen to occur in a merged record.
            changes = ledger.get("changes")
            incoming = changes if isinstance(changes, list) else [ledger.get("event")]
            for event in incoming:
                if not isinstance(event, dict) or not event.get("id"):
                    continue
                key = (ledger.get("roundId"), event["id"])
                identity = (event.get("sequence"), event.get("seat"))
                cards = list(event.get("cards", []))
                time = ledger.get("elapsedMs")
                if key not in events:
                    events[key] = {"identity": identity, "roundId": key[0],
                                   "id": event["id"], "sequence": identity[0], "seat": identity[1],
                                   "elapsedMs": time, "firstConfirmedElapsedMs": time,
                                   "updatedAtElapsedMs": time, "initialCards": cards.copy(),
                                   "cards": cards.copy(), "finalCards": cards.copy(), "revisions": []}
                    continue
                previous = events[key]
                if previous["identity"] != identity:
                    conflicts.append({"roundId": key[0], "eventId": key[1], "elapsedMs": time,
                                      "previousSequence": previous["sequence"], "incomingSequence": identity[0],
                                      "previousSeat": previous["seat"], "incomingSeat": identity[1]})
                    continue
                # Strict same-track automatic review can expand an already
                # confirmed event. Card changes are evidence revisions, not a
                # second play or a change of event identity. Their correctness
                # still requires independent video annotation.
                before, after = Counter(previous["finalCards"]), Counter(cards)
                if before != after:
                    extends = len(cards) > len(previous["finalCards"]) and all(after[c] >= n for c, n in before.items())
                    previous["revisions"].append({"elapsedMs": time, "cards": cards.copy(),
                                                  "kind": "extension" if extends else "card_change"})
                    previous["updatedAtElapsedMs"] = time
                    previous["cards"] = cards.copy()
                    previous["finalCards"] = cards.copy()
        timings = {}
        for field in ("convertMs", "preprocessMs", "inferenceMs", "decodeMs", "ocrAgeMs"):
            samples = [float(r[field]) for r in frames if isinstance(r.get(field), (int, float))]
            timings[field] = {"samples": len(samples), "median": percentile(samples, 0.5), "p95": percentile(samples, 0.95)}
        output.append({
            "sessionId": session,
            "metadata": next((r for r in unique if r.get("type") == "session"), None),
            "records": len(unique), "frames": len(frames),
            "scenes": dict(Counter(r.get("scene", "UNKNOWN") for r in frames)),
            "backendFrames": dict(Counter(r.get("backend", "unknown") for r in frames)),
            "frameGapMs": {"median": percentile(gaps, 0.5), "p95": percentile(gaps, 0.95), "max": max(gaps, default=None)},
            "timingsMs": timings,
            "ledgerPhases": dict(Counter(r.get("phase", "unknown") for r in ledgers)),
            "unavailable": [{"roundId": r.get("roundId"), "elapsedMs": r.get("elapsedMs"), "reason": r.get("reason")} for r in ledgers if r.get("phase") == "UNAVAILABLE"],
            "confirmedUniqueEvents": len(events),
            "revisedEvents": sum(bool(e["revisions"]) for e in events.values()),
            "eventRevisions": sum(len(e["revisions"]) for e in events.values()),
            "events": [{k: v for k, v in e.items() if k != "identity"} for e in events.values()],
            "eventIdentityConflicts": conflicts,
        })
    return {"schemaVersion": 2, "malformedJsonLines": malformed, "sessions": output,
            "accuracyVerdict": "not assessed: independent full-game video annotation is required",
            "timingCaveat": "Stage timings are not visible-event-to-overlay latency. Align the video to elapsedRealtime and annotate stable-card/display times independently."}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path, help="Local trace.jsonl or logcat.stdout.log files; no phone/network access")
    parser.add_argument("--output", type=Path, help="Optional local JSON summary")
    args = parser.parse_args()
    rows, malformed = [], 0
    for path in args.inputs:
        with path.open("r", encoding="utf-8-sig", errors="replace") as source:
            parsed, errors = records(source)
            rows.extend(parsed)
            malformed += errors
    encoded = json.dumps(summarize(rows, malformed), ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(encoded + "\n", encoding="utf-8")
    else:
        print(encoded)


if __name__ == "__main__":
    main()
