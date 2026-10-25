"""Build private phone-replay tapes from explicitly selected diagnostic sessions.

The tape preserves only fields present in the selected trace. Missing semantic
evidence stays false/unknown; no opening-choice proof, counts, or images are
inferred. This does not run the model or establish independent card truth.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import sys
import zipfile

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("replay_visibility", Path(__file__).with_name("prepare-replay-visibility.py"))
VISIBILITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VISIBILITY)

SEATS = ("SELF", "PREVIOUS", "OPPOSITE", "NEXT")
SCENES = {"TABLE", "DEALING", "LOBBY", "TRIBUTE", "TEAMMATE", "RESULT", "UNKNOWN"}
RANKS = {"BIG", "SMALL", "A", "K", "Q", "J", "TEN", "NINE", "EIGHT", "SEVEN", "SIX", "FIVE", "FOUR", "THREE", "TWO"}
SUITS = {"RED", "BLACK", "NONE"}
EMPTY_REASONS = {"no_prior_presentation", "detected_glyphs", "surface_not_calibrated",
                 "table_surface_visible", "foreground_or_animation", "overlay_occlusion"}


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def checked_session(value: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", value):
        raise ValueError("Invalid session ID")
    return value


def bool_field(value) -> bool:
    return value if type(value) is bool else False


def glyph_vector(frame: dict) -> str:
    glyphs = frame.get("glyphs")
    if glyphs is None:
        return ""
    if not isinstance(glyphs, list):
        raise ValueError("Malformed glyph vector")
    encoded = []
    for glyph in glyphs:
        if not isinstance(glyph, list) or len(glyph) != 6 or not isinstance(glyph[0], str):
            raise ValueError("Malformed glyph entry")
        if glyph[0] in {"BIG", "SMALL"}:
            label = [glyph[0], "NONE"]
        else:
            label = glyph[0].split(":")
        if len(label) != 2 or label[0] not in RANKS or label[1] not in SUITS:
            raise ValueError("Unknown glyph label")
        values = glyph[1:]
        if any(type(value) not in (int, float) or not math.isfinite(value) for value in values):
            raise ValueError("Non-finite glyph field")
        confidence, left, top, right, bottom = values
        if not 0 <= confidence <= 1 or any(not 0 <= value <= 1 for value in (left, top, right, bottom)):
            raise ValueError("Glyph field outside normalized range")
        if not left < right or not top < bottom:
            raise ValueError("Invalid glyph geometry")
        encoded.append(",".join([label[0], label[1], *(format(float(value), ".8g") for value in values)]))
    return "|".join(encoded)


def empty_evidence_fields(frame: dict) -> tuple[str, bool, str]:
    evidence = frame.get("emptyEvidence")
    if not isinstance(evidence, dict):
        evidence = {}
    observed, reasons = [], []
    for seat in SEATS:
        reading = evidence.get(seat)
        observed.append(bool_field(reading.get("observed")) if isinstance(reading, dict) else False)
        reason = reading.get("reason") if isinstance(reading, dict) else None
        reasons.append(reason if isinstance(reason, str) and reason in EMPTY_REASONS else "?")
    self_reading = evidence.get("SELF")
    self_clean = (isinstance(self_reading, dict) and self_reading.get("observed") is True and
                  self_reading.get("reason") == "table_surface_visible")
    return ",".join(str(value).lower() for value in observed), self_clean, ",".join(reasons)


def encode_counts(frame: dict) -> tuple[str, int]:
    counts = frame.get("remainingHandCounts")
    if counts is None:
        return "", 0
    if not isinstance(counts, dict):
        return "", 1
    encoded, omitted = [], 0
    for seat in SEATS[1:]:
        value = counts.get(seat)
        if value is None:
            continue
        if (not isinstance(value, dict) or type(value.get("count")) is not int or
                type(value.get("sourceTimeMs")) is not int or not 0 <= value["count"] <= 27 or
                value["sourceTimeMs"] < 0):
            omitted += 1
            continue
        encoded.append(f"{seat},{value['count']},{value['sourceTimeMs']}")
    return "|".join(encoded), omitted


def encode_single_identities(frame: dict) -> str:
    identities = frame.get("singleCardIdentities")
    if not isinstance(identities, dict):
        return ""
    colors = {"CLUB": "BLACK", "SPADE": "BLACK", "HEART": "RED", "DIAMOND": "RED"}
    elapsed = frame.get("elapsedMs")
    encoded = []
    for seat in SEATS[1:]:
        reading = identities.get(seat)
        if not isinstance(reading, dict):
            continue
        label = reading.get("card", "").split(":")
        source = reading.get("sourceTimeMs")
        scores = (reading.get("similarity"), reading.get("margin"))
        if (len(label) != 2 or label[0] not in RANKS-{ "BIG", "SMALL" } or
                colors.get(reading.get("suit")) != label[1] or type(source) is not int or
                type(elapsed) is not int or not 0 <= elapsed-source <= 500 or
                frame.get("groups", {}).get(seat) != [reading["card"]] or
                frame.get("regionObserved", {}).get(seat) is not True or
                not all(type(v) in (int, float) and math.isfinite(v) for v in scores) or
                not .80 <= scores[0] <= 1 or not .12 <= scores[1] <= 1):
            continue
        encoded.append(",".join(map(str,(seat,label[0],reading["suit"],*scores,source))))
    return "|".join(encoded)


def encode_early_finish_hints(frame: dict) -> str:
    # These are the original, dimension/epoch-qualified OCR snapshot hints
    # already emitted by TencentRecognizer, not inferred ranks or card truth.
    elapsed, age = frame.get("elapsedMs"), frame.get("sceneSourceAgeMs")
    hints, source_masks = frame.get("sceneSourceHints"), frame.get("sceneSourceMasks")
    masks = frame.get("overlayMasks")
    if (type(elapsed) is not int or type(age) is not int or not 0 <= age <= 1000 or
            elapsed-age < 0 or not isinstance(hints, list) or not isinstance(masks, list) or
            not isinstance(source_masks, list) or frame.get("adjusting") is True or
            frame.get("scene") not in {"TABLE", "TEAMMATE"}):
        return "?"
    all_masks = source_masks + masks
    def valid(box):
        return (isinstance(box, list) and len(box) == 4 and
                all(type(x) in (int, float) and math.isfinite(x) and 0 <= x <= 1 for x in box) and
                box[0] < box[2] and box[1] < box[3])
    if any(not valid(box) for box in all_masks):
        return "?"
    result = []
    for hint in hints:
        if not isinstance(hint, dict) or hint.get("text") not in {"头游", "二游"}:
            continue
        box = hint.get("box")
        if not valid(box) or any(m[0] < box[2]+.0001 and box[0]-.0001 < m[2] and
                                 m[1] < box[3]+.0001 and box[1]-.0001 < m[3] for m in all_masks):
            continue
        result.append(",".join((hint["text"], str(elapsed-age), *(str(x) for x in box))))
    return "|".join(result)


def frame_to_tape_row(frame: dict) -> tuple[str, int]:
    elapsed = frame.get("elapsedMs")
    if type(elapsed) is not int or elapsed < 0:
        raise ValueError("Frame has no valid source timestamp")
    scene = frame.get("scene")
    if scene not in SCENES:
        scene = "UNKNOWN"
    reason = frame.get("reason")
    # Current production emits `reason` only for an unobservable/partial frame.
    # Keep those gaps explicitly UNKNOWN instead of assigning a recognized scene.
    capture_gap = bool_field(frame.get("captureGap"))
    # In the production trace, a nonempty reason is emitted for an unobserved
    # or partial frame. Preserve that source evidence even if an older/newer
    # schema also carries a contradictory false captureGap field.
    if isinstance(reason, str) and reason:
        capture_gap = True
    # A scene label without any glyph payload cannot establish an observed
    # table frame. Keep it as a gap instead of treating it as semantic proof.
    if not isinstance(frame.get("glyphs"), list):
        capture_gap = True
    if capture_gap:
        scene = "UNKNOWN"
    adjusting = frame.get("adjusting")
    if type(adjusting) is not bool:
        adjusting = isinstance(reason, str) and reason == "adjustment_occlusion"
    observed, self_clean, empty_reasons = empty_evidence_fields(frame)
    self_finished = bool_field(frame.get("selfFinished"))
    passing = frame.get("passing")
    if passing is None:
        passing = []
    if not isinstance(passing, list) or any(not isinstance(seat, str) or seat not in SEATS for seat in passing):
        raise ValueError("Malformed passing-seat evidence")
    dealing = bool_field(frame.get("dealingChoicesObserved"))
    masks = VISIBILITY.encode_masks(frame)
    opening_source = frame.get("openingChoiceSourceTimeMs")
    opening_field = str(opening_source) if type(opening_source) is int and opening_source >= 0 else "?"
    counts, omitted_counts = encode_counts(frame)
    row = ";".join((str(elapsed), scene, str(self_finished).lower(), observed,
                     str(self_clean).lower(), str(adjusting).lower(), glyph_vector(frame),
                     ",".join(passing), str(capture_gap).lower(), str(dealing).lower(),
                     masks, opening_field, counts, empty_reasons))
    if isinstance(frame.get("singleCardIdentities"), dict) or isinstance(frame.get("observedPasses"), dict):
        row += ";" + encode_single_identities(frame)
    if isinstance(frame.get("observedPasses"), dict):
        passes = frame["observedPasses"]
        row += ";" + "|".join(f"{seat},{source}" for seat, source in passes.items()
                               if seat in SEATS[1:] and type(source) is int and 0 <= elapsed-source <= 1000)
    early_hints = encode_early_finish_hints(frame)
    if early_hints != "?":
        columns = row.split(";")
        columns += [""] * (16-len(columns))
        row = ";".join(columns + [early_hints])
    return row, omitted_counts


def parse_trace(zipped: zipfile.ZipFile, member: str | list[str], session: str):
    digest = hashlib.sha256()
    header_seen = False
    rows, omitted_counts = [], 0
    probe_timestamps = set()
    members = [member] if isinstance(member, str) else member
    for part in members:
        with zipped.open(part) as stream:
            for raw in stream:
                digest.update(raw)
                try:
                    record = json.loads(raw)
                except (ValueError, UnicodeError):
                    raise ValueError("Malformed JSON in selected trace")
                if not isinstance(record, dict):
                    continue
                record_session = record.get("sessionId")
                if record.get("type") == "session":
                    if record_session != session:
                        raise ValueError("Session header does not match selected ID")
                    header_seen = True
                elif record.get("type") == "frame":
                    if record_session is not None and record_session != session:
                        raise ValueError("Frame session ID does not match selected ID")
                    row, omitted = frame_to_tape_row(record)
                    rows.append(row)
                    omitted_counts += omitted
                    image_time = record.get("imageTimestampNs")
                    if type(image_time) is int and image_time >= 0:
                        probe_timestamps.add(image_time)
    if not header_seen:
        raise ValueError("Selected trace has no matching session header")
    if not rows:
        raise ValueError("Selected session has no frames")
    return "\n".join(rows) + "\n", digest.hexdigest(), omitted_counts, probe_timestamps


def indexed_probe_images(zipped: zipfile.ZipFile, timestamps: set[int]) -> list[dict]:
    if not timestamps:
        return []
    by_time: dict[int, list[str]] = {}
    names = set(zipped.namelist())
    for name in zipped.namelist():
        if not name.startswith("capture-probes/") or not name.endswith("/probe.json"):
            continue
        try:
            probe = json.loads(zipped.read(name))
        except (ValueError, UnicodeError):
            continue
        image_time = probe.get("imageTimestampNs") if isinstance(probe, dict) else None
        if type(image_time) is int and image_time in timestamps:
            by_time.setdefault(image_time, []).append(name.rsplit("/", 1)[0])
    results = []
    for image_time, folders in sorted(by_time.items()):
        if len(set(folders)) != 1:
            continue
        folder = folders[0]
        for image_name in ("legacy.png", "converted.png", "masked.png", "model-input.png"):
            member = f"{folder}/{image_name}"
            if member in names:
                results.append({"imageTimestampNs": image_time, "member": member,
                                "sha256": sha256_bytes(zipped.read(member))})
    return results


def archive_images(zipped: zipfile.ZipFile) -> list[dict]:
    image_suffixes = {".jpg", ".jpeg", ".png", ".webp"}
    return [{"member": name, "sha256": sha256_bytes(zipped.read(name))}
            for name in sorted(set(zipped.namelist()))
            if Path(name).suffix.lower() in image_suffixes]


def prepare(archive: Path, sessions: list[str], output: Path, manifest_path: Path) -> dict:
    archive = Path(archive).resolve(strict=True)
    if not archive.is_file():
        raise ValueError("Archive is not a file")
    if not sessions or len(set(sessions)) != len(sessions):
        raise ValueError("Pass one or more unique --session values")
    sessions = [checked_session(session) for session in sessions]
    output = Path(output).resolve()
    manifest_path = Path(manifest_path).resolve()
    if output.exists():
        raise ValueError("Output must be a new directory")
    if manifest_path.parent != output or manifest_path.exists():
        raise ValueError("Manifest must be a new file inside the output directory")
    archive_hash = sha256_file(archive)
    result = {"schemaVersion": 2, "kind": "phone_logged_session_replay_tapes",
              "archiveSha256": archive_hash,
              "scope": "source_trace_replay_only_no_image_or_card_inference_empty_reason_tokens",
              "archiveImages": [], "sources": []}
    output.mkdir(parents=True, exist_ok=False)
    try:
        with zipfile.ZipFile(archive) as zipped:
            names = zipped.namelist()
            result["archiveImages"] = archive_images(zipped)
            for session in sessions:
                member = f"sessions/{session}/trace.jsonl"
                if names.count(member) != 1:
                    raise ValueError("Selected session trace is missing or ambiguous")
                prefix = f"sessions/{session}/"
                pattern = re.compile(re.escape(prefix) + r"trace\.previous(?:\.([2-9]|[1-9][0-9]+))?\.jsonl$")
                rotated = [(int(match.group(1) or 1), name) for name in names
                           if (match := pattern.fullmatch(name))]
                parts = [name for _, name in sorted(rotated, reverse=True)] + [member]
                if len(parts) != len(set(parts)):
                    raise ValueError("Selected session trace part is ambiguous")
                tape_text, trace_hash, omitted_counts, probe_times = parse_trace(zipped, parts, session)
                tape_bytes = tape_text.encode("utf-8")
                tape_path = output / f"{session}.tape"
                tape_path.write_bytes(tape_bytes)
                image_sources = indexed_probe_images(zipped, probe_times)
                result["sources"].append({
                    "session": session,
                    "traceMember": member,
                    "traceSha256": trace_hash,
                    "traceParts": [{"member": part, "sha256": sha256_bytes(zipped.read(part))} for part in parts],
                    "tapeFile": tape_path.name,
                    "tapeSha256": sha256_bytes(tape_bytes),
                    "frameCount": len(tape_text.splitlines()),
                    "sourceImageSha256s": image_sources,
                    "omittedMalformedCountEntries": omitted_counts,
                })
        data = json.dumps(result, indent=2, ensure_ascii=False).encode("utf-8")
        pending = manifest_path.with_name(manifest_path.name + ".pending")
        pending.write_bytes(data)
        pending.replace(manifest_path)
    except Exception:
        # Keep any partial private files for diagnosis; the absent manifest marks
        # the output as incomplete and prevents it being mistaken for a replay set.
        raise
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--session", action="append", required=True, help="Session ID; repeat to select more sessions")
    parser.add_argument("--output", type=Path, required=True, help="A new private directory for the tape files")
    parser.add_argument("--manifest", type=Path, required=True, help="New provenance JSON inside --output")
    args = parser.parse_args()
    try:
        result = prepare(args.archive, args.session, args.output, args.manifest)
    except Exception as error:
        reason = str(error) if isinstance(error, ValueError) else type(error).__name__
        print(json.dumps({"preparationError": reason}), file=sys.stderr)
        return 1
    print(json.dumps({"sessions": len(result["sources"]),
                      "frames": sum(source["frameCount"] for source in result["sources"]),
                      "archiveSha256": result["archiveSha256"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
