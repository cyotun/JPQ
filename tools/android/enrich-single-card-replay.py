"""Add only source-recorded physical single-card evidence to a private replay.

No image/model/OCR inference is performed. Legacy traces may explicitly opt in
to joining their existing straight_flush readings at the EXACT frame timestamp.
Both input and output hashes plus each joined source line are recorded. The
original tape is never modified. This does not establish independent card truth.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
from pathlib import Path

SEATS = ("PREVIOUS", "OPPOSITE", "NEXT")
SUITS = {"CLUB": "BLACK", "SPADE": "BLACK", "HEART": "RED", "DIAMOND": "RED"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def valid(reading, elapsed):
    if not isinstance(reading, dict):
        return False
    card = reading.get("card", "").split(":")
    suit = reading.get("suit")
    source = reading.get("sourceTimeMs")
    scores = (reading.get("similarity"), reading.get("margin"))
    return (len(card) == 2 and card[0] not in {"BIG", "SMALL"} and
            SUITS.get(suit) == card[1] and type(source) is int and 0 <= elapsed-source <= 500 and
            all(type(x) in (int, float) and math.isfinite(x) for x in scores) and
            .80 <= scores[0] <= 1 and .12 <= scores[1] <= 1)


def select(frame, flush_records, allow_legacy):
    elapsed = frame["elapsedMs"]
    if frame.get("adjusting") or frame.get("reason") or frame.get("captureGap") or frame.get("ambiguous") != 0:
        return {}, "unqualified_frame"
    explicit = frame.get("singleCardIdentities")
    if isinstance(explicit, dict):
        return {s: r for s, r in explicit.items() if s in SEATS and valid(r, elapsed) and
                frame.get("groups", {}).get(s) == [r["card"]] and
                frame.get("regionObserved", {}).get(s) is True}, "explicit_frame_reading"
    if not allow_legacy or len(flush_records) != 1:
        return {}, "no_unique_exact_timestamp_record"
    record = flush_records[0]
    if record.get("elapsedMs") != elapsed or record.get("sessionId") != frame.get("sessionId"):
        return {}, "source_mismatch"
    readings = [r for r in record.get("readings", []) if r.get("placement") == "TABLE"]
    selected = {}
    for seat in SEATS:
        cards = frame.get("groups", {}).get(seat)
        if not isinstance(cards, list) or len(cards) != 1 or frame.get("regionObserved", {}).get(seat) is not True:
            continue
        card = cards[0]
        # A TABLE reading has no seat/geometry field in legacy logs. Its card
        # must occur exactly once in the entire table, including unassigned cards.
        matches = [g for g in frame.get("glyphs", []) if len(g) == 6 and g[0] == card and
                   (g[3]+g[5])/2 < .55]
        candidates = [r for r in readings if r.get("card") == card]
        if len(matches) != 1 or len(candidates) != 1:
            continue
        candidate = {**candidates[0], "sourceTimeMs": elapsed}
        if candidate.get("reason") == "shape_matched" and valid(candidate, elapsed):
            selected[seat] = candidate
    return selected, "legacy_exact_timestamp_unique_table_symbol"


def enrich(trace_path, tape_path, output_path, allow_legacy=False):
    trace_path, tape_path, output_path = map(Path, (trace_path, tape_path, output_path))
    if output_path.resolve() in {trace_path.resolve(), tape_path.resolve()} or output_path.exists():
        raise ValueError("Output must be a new file, preserving original evidence")
    trace_bytes, tape_bytes = trace_path.read_bytes(), tape_path.read_bytes()
    frames, flushes, lines = {}, {}, {}
    for line_number, line in enumerate(trace_bytes.splitlines(), 1):
        record = json.loads(line)
        elapsed = record.get("elapsedMs")
        if record.get("type") == "frame":
            if elapsed in frames:
                raise ValueError("Duplicate frame timestamp")
            frames[elapsed] = record
            lines[("frame", elapsed)] = (line_number, sha(line))
        elif record.get("type") == "straight_flush":
            flushes.setdefault(elapsed, []).append(record)
            lines[("straight_flush", elapsed)] = (line_number, sha(line))
    output, evidence = [], []
    rows = tape_bytes.decode("utf-8-sig").splitlines()
    if len(rows) != len(frames):
        raise ValueError("Tape and selected trace frame counts differ")
    for row in rows:
        fields = row.split(";")
        if len(fields) != 14:
            raise ValueError("Expected the original 14-field tape")
        elapsed = int(fields[0])
        frame = frames.get(elapsed)
        if frame is None:
            raise ValueError("Tape has no exact source frame")
        selected, method = select(frame, flushes.get(elapsed, []), allow_legacy)
        encoded = []
        for seat, reading in sorted(selected.items()):
            rank = reading["card"].split(":")[0]
            encoded.append(",".join(map(str, (seat, rank, reading["suit"], reading["similarity"],
                                              reading["margin"], reading["sourceTimeMs"]))))
            evidence.append({"elapsedMs": elapsed, "seat": seat, "method": method,
                             "reading": reading, "frameSource": lines[("frame", elapsed)],
                             "symbolSource": lines.get(("straight_flush", elapsed)) if method.startswith("legacy") else None})
        output.append(row + ";" + "|".join(encoded))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    data = ("\n".join(output) + "\n").encode("utf-8")
    output_path.write_bytes(data)
    result = {"scope": "recorded_physical_symbol_evidence_not_new_pixel_inference_or_independent_card_truth",
              "legacyJoinEnabled": allow_legacy, "trace": str(trace_path.resolve()), "traceSha256": sha(trace_bytes),
              "originalTape": str(tape_path.resolve()), "originalTapeSha256": sha(tape_bytes),
              "outputTapeSha256": sha(data), "frames": len(rows), "joinedReadings": len(evidence), "evidence": evidence}
    output_path.with_suffix(".provenance.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--trace", required=True, type=Path)
    parser.add_argument("--tape", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--allow-recorded-straight-flush", action="store_true")
    args = parser.parse_args()
    result = enrich(args.trace, args.tape, args.output, args.allow_recorded_straight_flush)
    print(json.dumps({k: result[k] for k in ("scope", "frames", "joinedReadings", "outputTapeSha256")}))
