"""Fresh desktop FULL-model inference on explicitly selected private evidence.

This is not Android capture or ML Kit OCR emulation. Video scene/finish labels
are reviewed manifest inputs, never inferred from the ledger being tested.
All output stays local; the program never accesses a phone or the network.
"""
from __future__ import annotations

import argparse
import base64
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import sys
import time
import zlib

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
for location in (ROOT / ".research-tools" / "litert-python", ROOT / ".research-tools" / "video-python",
                 ROOT / ".research-tools" / "litert-2.2.0"):
    if location.is_dir():
        sys.path.insert(0, str(location))


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def checked_file(value: dict) -> Path:
    path = Path(value["path"]).resolve(strict=True)
    expected = value["sha256"]
    if not isinstance(expected, str) or len(expected) != 64 or sha256(path) != expected:
        raise ValueError("Evidence hash differs from the reviewed manifest")
    return path


def glyph_key(g: dict) -> str:
    return g["rank"] if g["rank"] in {"BIG", "SMALL"} else f'{g["rank"]}:{g["color"]}'


def phone_compare(glyphs: list[dict], expected: list[str] | None) -> dict:
    # Exactly the same spatial filter as the current TencentRecognizer.
    filtered = [g for g in glyphs if (g["bbox"][1] + g["bbox"][3]) / 2 > .13
                and .10 <= (g["bbox"][0] + g["bbox"][2]) / 2 <= .88
                and g["bbox"][2] - g["bbox"][0] < .12 and g["bbox"][3] - g["bbox"][1] > .02]
    # ReferenceCardDetectorFixtureTest.full.cardColorMultiset contains every
    # NMS detection; its hand/seat partition applies the spatial filter later.
    actual = Counter(glyph_key(g) for g in glyphs)
    return {"filteredCount": len(filtered), "phoneReferencePresent": expected is not None,
            "phoneColorMultisetMatches": actual == Counter(expected) if expected is not None else None,
            "actualColorMultiset": sorted(actual.elements()),
            "scope": "phone_fixture_consistency_not_independent_card_truth"}


def validate_video(manifest: dict) -> tuple[Path, list[dict]]:
    video = checked_file(manifest["source"])
    window = manifest["reviewedWindowSeconds"]
    segments = manifest["segments"]
    def finite(value):
        return type(value) in (int, float) and math.isfinite(value)
    if (not all(finite(v) for v in window) or not finite(manifest["safeReviewEndSeconds"])
            or any(not finite(s["start"]) or not finite(s["end"]) for s in segments)):
        raise ValueError("Video timestamps must be finite numbers")
    if len(window) != 2 or not 0 <= window[0] < window[1] or not segments:
        raise ValueError("An explicit reviewed video interval and scene labels are required")
    cursor = window[0]
    for s in segments:
        if s["start"] != cursor or not s["start"] < s["end"] <= window[1]:
            raise ValueError("Scene segments must cover the reviewed interval without gaps or overlap")
        if s["scene"] not in {"LOBBY", "DEALING", "TABLE", "TEAMMATE", "RESULT", "TRIBUTE", "UNKNOWN"}:
            raise ValueError("Unknown reviewed scene")
        if type(s["selfFinished"]) is not bool:
            raise ValueError("Missing explicit reviewed finish label")
        cursor = s["end"]
    if cursor != window[1] or type(manifest["expectedFrames"]) is not int or manifest["expectedFrames"] <= 0:
        raise ValueError("Video range must have an exact reviewed frame count")
    # Old recordings can contain app switches after this bound. Never decode
    # or display beyond the separately reviewed safe end.
    if not window[1] <= manifest["safeReviewEndSeconds"]:
        raise ValueError("Requested range exceeds reviewed safe end")
    return video, segments


def run(manifest_path: Path, output: Path, model: Path, resize_mode: str) -> dict:
    from PIL import Image, ImageDraw
    from model_detector import FullDetector

    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    if manifest.get("schemaVersion") != 1 or manifest.get("kind") not in {"images", "video"}:
        raise ValueError("Unsupported evidence manifest")
    output = output.resolve()
    if not output.is_relative_to((ROOT / "captures").resolve()) or output == (ROOT / "captures").resolve():
        raise ValueError("Outputs must be a new private directory below captures")
    # Validate every input before creating a report directory.
    if manifest["kind"] == "images":
        sources = [(entry, checked_file(entry)) for entry in manifest["images"]]
        if not sources or len({e[0]["id"] for e in sources}) != len(sources):
            raise ValueError("Image entries must have unique IDs")
    else:
        video, segments = validate_video(manifest)
    detector = FullDetector(model, threads=4, resize_mode=resize_mode)
    scripts = [Path(__file__).resolve(), Path(__file__).with_name("model_detector.py").resolve()]
    code_hashes = {str(p.relative_to(ROOT)): sha256(p) for p in scripts}
    output.mkdir(parents=True, exist_ok=False)
    (output / "manifest.json").write_bytes(manifest_path.read_bytes())
    result = {"schemaVersion": 1, "status": "running", "kind": manifest["kind"],
              "manifestSha256": sha256(manifest_path), "model": detector.metadata,
              "freshModelInference": True, "androidCaptureTested": False, "androidOcrTested": False,
              "driverHashes": code_hashes,
              "sceneLabels": "explicit_reviewed_inputs" if manifest["kind"] == "video" else None,
              "ledgerVerified": False, "samples": [], "frames": 0}
    started = time.perf_counter()
    try:
        with (output / "predictions.jsonl").open("w", encoding="utf-8") as predictions:
            if manifest["kind"] == "images":
                for entry, path in sources:
                    with Image.open(path) as opened:
                        image = opened.convert("RGB")
                    if "resizeTo" in entry:
                        image = image.resize(tuple(entry["resizeTo"]), Image.Resampling.BILINEAR)
                    detection = detector.predict(image)
                    comparison = phone_compare(detection["glyphs"], entry.get("expectedColorMultiset"))
                    record = {"id": entry["id"], "sourceSha256": entry["sha256"],
                              "width": image.width, "height": image.height, **detection, **comparison}
                    predictions.write(json.dumps(record, separators=(",", ":")) + "\n")
                    result["samples"].append({k: v for k, v in record.items() if k != "glyphs"})
                    result["frames"] += 1
            else:
                import av
                last_progress = time.perf_counter()
                last_pts = None
                index = 0
                # This tape deliberately retains the older shared-core replay
                # adapter's 512-wide surface pixels, not Android OCR or exact
                # full-resolution sampling parity. Raw model input stays full.
                result["ledgerAdapter"] = "PhoneVideoReplay; reviewed semantics and 512-wide surface approximation"
                with (output / "model-replay.tape").open("w", encoding="utf-8") as tape, av.open(str(video)) as container:
                    for frame in container.decode(video=0):
                        if frame.pts is None:
                            raise ValueError("Decoded frame has no source timestamp")
                        pts = float(frame.pts * frame.time_base)
                        if pts < manifest["reviewedWindowSeconds"][0]:
                            continue
                        if pts >= manifest["reviewedWindowSeconds"][1]:
                            break
                        if last_pts is not None and pts <= last_pts:
                            raise ValueError("Video timestamps are not strictly increasing")
                        last_pts = pts
                        while pts >= segments[index]["end"]:
                            index += 1
                        segment = segments[index]
                        image = frame.to_image().convert("RGB")
                        # Masks must come from reviewed physical captures, never
                        # erase new model failures or pretend to recover pixels.
                        for box in manifest.get("inputMasks", []):
                            if len(box) != 4 or not 0 <= box[0] < box[2] <= 1 or not 0 <= box[1] < box[3] <= 1:
                                raise ValueError("Invalid explicit source mask")
                            ImageDraw.Draw(image).rectangle(tuple(round(v * (image.width if i % 2 == 0 else image.height))
                                                                 for i, v in enumerate(box)), fill=(38, 38, 38))
                        detection = detector.predict(image)
                        record = {"ptsSeconds": pts, "timeMs": round(pts * 1000),
                                  "width": image.width, "height": image.height, **detection}
                        predictions.write(json.dumps(record, separators=(",", ":")) + "\n")
                        surface_h = round(512 * image.height / image.width)
                        rgb = image.resize((512, surface_h), Image.Resampling.BILINEAR).tobytes()
                        encoded = "|".join(",".join(map(str, [g["rank"], g["color"], g["conf"], *g["bbox"]]))
                                           for g in detection["glyphs"])
                        tape.write(";".join([str(record["timeMs"]), segment["scene"], "512", str(surface_h),
                                             base64.b64encode(zlib.compress(rgb, 1)).decode("ascii"), encoded,
                                             str(segment["selfFinished"]).lower()]) + "\n")
                        result["frames"] += 1
                        if time.perf_counter() - last_progress >= 20:
                            print(json.dumps({"frames": result["frames"], "sourcePTS": pts}), flush=True)
                            last_progress = time.perf_counter()
                if result["frames"] != manifest["expectedFrames"]:
                    raise ValueError("Decoded frame count differs from the reviewed manifest")
        if manifest["kind"] == "images":
            for entry, _ in sources:
                checked_file(entry)
        else:
            checked_file(manifest["source"])
        if any(sha256(p) != code_hashes[str(p.relative_to(ROOT))] for p in scripts):
            raise ValueError("Model driver changed during inference; discard this run")
        result["codeAndInputsUnchanged"] = True
        result["status"] = "inference_complete"
        comparisons = [s for s in result["samples"] if s["phoneReferencePresent"]]
        result["phoneFixtureComparisons"] = len(comparisons)
        result["phoneFixtureMismatches"] = (sum(s["phoneColorMultisetMatches"] is False for s in comparisons)
                                              if comparisons else None)
    except BaseException as error:
        result["status"] = "failed"
        result["failure"] = type(error).__name__
        raise
    finally:
        result["elapsedSeconds"] = time.perf_counter() - started
        (output / "report.json").write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", type=Path, default=ROOT / "app/src/main/assets/models/cards.tflite")
    parser.add_argument("--resize", choices=("bilinear", "nearest"), default="bilinear")
    args = parser.parse_args()
    result = run(args.manifest, args.output, args.model, args.resize)
    print(json.dumps({"status": result["status"], "frames": result["frames"],
                      "phoneFixtureMismatches": result["phoneFixtureMismatches"]}))
    return 1 if result["phoneFixtureMismatches"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
