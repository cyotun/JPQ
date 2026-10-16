"""Append original source mask geometry to private replay copies; never rewrite tapes.

Only a unique raw frame with the same timestamp and full glyph vector can supply
metadata. Missing/malformed source metadata stays explicitly unknown. This does
not add cards, scene labels, grade labels, counts, or finish evidence.
"""
import argparse
import hashlib
import json
import math
import re
from pathlib import Path
import zipfile


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def same_glyphs(encoded, glyphs):
    expected = [] if not encoded else [item.split(",") for item in encoded.split("|")]
    if not isinstance(glyphs, list) or len(glyphs) != len(expected):
        return False
    for fields, raw in zip(expected, glyphs):
        if not isinstance(raw, list) or len(raw) != 6 or len(fields) != 7:
            return False
        label = fields[0] if fields[1] == "NONE" else fields[0] + ":" + fields[1]
        if label != raw[0]:
            return False
        try:
            if any(not math.isfinite(float(a)) or not math.isfinite(float(b)) or abs(float(a)-float(b)) > .00011
                   for a, b in zip(fields[2:], raw[1:])):
                return False
        except (TypeError, ValueError):
            return False
    return True


def encode_masks(frame):
    masks = frame.get("overlayMasks")
    if not isinstance(masks, list):
        return "?"
    result = []
    for box in masks:
        if (not isinstance(box, list) or len(box) != 4 or
                any(type(x) not in (int, float) or not math.isfinite(x) or not 0 <= x <= 1 for x in box) or
                not box[0] < box[2] or not box[1] < box[3]):
            return "?"
        # Logged rectangles are rounded to four decimals; conservatively retain
        # their rounding uncertainty instead of declaring a touching edge clear.
        padded = [max(0, box[0]-.0001), max(0, box[1]-.0001), min(1, box[2]+.0001), min(1, box[3]+.0001)]
        result.append(",".join(f"{x:.5f}" for x in padded))
    return "|".join(result)


def prepare(archive, inputs, output):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    result = {"archiveSha256": sha(archive), "scope": "original_masks_only_no_card_or_finish_inference", "sources": []}
    seen=set()
    with zipfile.ZipFile(archive) as zipped:
        for entry in inputs:
            session=entry["session"]
            if not isinstance(session,str) or not re.fullmatch(r"[A-Za-z0-9_-]+",session) or session in seen:
                raise ValueError("Invalid or duplicate source ID")
            seen.add(session)
            tape = Path(entry["path"])
            if tape.name!=session+".tape":
                raise ValueError("Tape name does not match source ID")
            if sha(tape) != entry["sha256"]:
                raise ValueError("Frozen tape hash mismatch")
            member = "sessions/" + entry["session"] + "/trace.jsonl"
            if zipped.namelist().count(member) != 1:
                raise ValueError("Missing or ambiguous original session trace")
            frames, duplicates, malformed = {}, set(), 0
            with zipped.open(member) as stream:
                for line in stream:
                    try:
                        frame = json.loads(line)
                    except (ValueError, UnicodeError):
                        malformed += 1
                        continue
                    if frame.get("type") != "frame" or type(frame.get("elapsedMs")) is not int:
                        continue
                    time = frame["elapsedMs"]
                    if time in frames:
                        duplicates.add(time)
                    frames[time] = (frame.get("glyphs"), encode_masks(frame))
            rows, known, unknown, mismatch = [], 0, 0, 0
            for line in tape.read_text(encoding="utf-8-sig").splitlines():
                fields = line.split(";")
                if len(fields) not in (9, 10):
                    raise ValueError("Unexpected original tape schema")
                time = int(fields[0]); masks = "?"
                raw = frames.get(time)
                if raw is not None and time not in duplicates:
                    if same_glyphs(fields[6], raw[0]):
                        masks = raw[1]
                    else:
                        mismatch += 1
                known += masks != "?"; unknown += masks == "?"
                # Preserve every original field verbatim. A legacy scene enum is
                # not upgraded to the new semantic doubling-controls proof.
                rows.append(line + (";false" if len(fields) == 9 else "") + ";" + masks)
            if len(rows) != entry["frames"]:
                raise ValueError("Frozen frame count mismatch")
            target = output / (entry["session"] + ".tape")
            target.write_text("\n".join(rows) + "\n", encoding="utf-8")
            result["sources"].append({"session": entry["session"], "originalTapeSha256": entry["sha256"],
                "replaySha256": sha(target), "frames": len(rows), "knownMaskFrames": known,
                "unknownMaskFrames": unknown, "glyphMismatches": mismatch, "malformedRawLines": malformed,
                "duplicateRawTimes": len(duplicates)})
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--inputs", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    args = parser.parse_args()
    manifest = prepare(args.archive, json.loads(args.inputs.read_text(encoding="utf-8-sig")), args.output)
    args.manifest.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps({"sources": len(manifest["sources"]),
        "knownMaskFrames": sum(s["knownMaskFrames"] for s in manifest["sources"]),
        "unknownMaskFrames": sum(s["unknownMaskFrames"] for s in manifest["sources"])}))
