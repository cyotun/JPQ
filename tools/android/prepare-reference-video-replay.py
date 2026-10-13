"""Prepare private timestamp-preserving JPEG inputs; never run model inference.

Example (the reviewed bound must be supplied explicitly):
  python -B tools/android/prepare-reference-video-replay.py \
    --approved-safe-end-pts 650 --expected-frames 2805

The result is a new captures directory. Its manifest.json and frames directory
can be staged together as external/reference-video-replay/input by the operator.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import sys
import time

# Importing installed decoder/Pillow packages must not write outside captures.
sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[2]
CAPTURES = ROOT / "captures"
EVIDENCE = CAPTURES / "phone" / "live-20261012-192441"
DEFAULT_VIDEO = EVIDENCE / "game-001.mkv"
DEFAULT_REVIEW = EVIDENCE / "analysis" / "round2-safe-review.json"
AUTHORIZED_START = Fraction(463)
AUTHORIZED_END = Fraction(650)
WIDTH, HEIGHT = 1920, 882
MASK_NORMALIZED = (0.0, 0.01, 0.4808, 0.125)
MASK_RGB = (38, 38, 38)


class PreparationError(Exception):
    """Short safe reason; decoder messages or private video contents are not printed."""


def seconds(value: str) -> Fraction:
    try:
        result = Fraction(value)
    except (ValueError, ZeroDivisionError) as error:
        raise argparse.ArgumentTypeError("PTS must be a finite decimal or fraction") from error
    if result < 0:
        raise argparse.ArgumentTypeError("PTS must be nonnegative")
    return result


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video", type=Path, default=DEFAULT_VIDEO)
    parser.add_argument("--output", type=Path, help="A new directory strictly below this workspace's captures")
    parser.add_argument("--start-pts", type=seconds, default=AUTHORIZED_START)
    parser.add_argument("--end-pts", type=seconds, default=AUTHORIZED_END)
    parser.add_argument("--approved-safe-end-pts", type=seconds, required=True,
                        help="Explicit reviewed upper PTS bound; never inferred from the video's duration")
    parser.add_argument("--review-metadata", type=Path,
                        help="Optional reviewed JSON containing safeReviewEndPtsSeconds")
    parser.add_argument("--expected-frames", type=int, help="Optional exact count check, e.g. 2805 for the full default interval")
    parser.add_argument("--jpeg-quality", type=int, default=95)
    return parser.parse_args()


def checked_paths(args: argparse.Namespace) -> tuple[Path, Path, Fraction | None]:
    if not AUTHORIZED_START <= args.start_pts <= args.end_pts <= AUTHORIZED_END:
        raise PreparationError("requested_range_outside_authorized_463_to_650_window")
    if args.end_pts > args.approved_safe_end_pts:
        raise PreparationError("requested_end_exceeds_explicit_review_bound")
    if not 90 <= args.jpeg_quality <= 100:
        raise PreparationError("jpeg_quality_must_be_90_to_100")
    if args.expected_frames is not None and args.expected_frames <= 0:
        raise PreparationError("expected_frame_count_must_be_positive")
    video = args.video.resolve(strict=True)
    if not video.is_file():
        raise PreparationError("video_is_not_a_file")
    review = args.review_metadata
    if review is None and video == DEFAULT_VIDEO.resolve() and DEFAULT_REVIEW.is_file():
        review = DEFAULT_REVIEW
    reviewed_end = None
    if review is not None:
        # The content is used only as a numeric safety bound, never printed or copied.
        review_data = json.loads(review.resolve(strict=True).read_text(encoding="utf-8"))
        reviewed_end = Fraction(str(review_data["safeReviewEndPtsSeconds"]))
        if args.end_pts > reviewed_end or args.approved_safe_end_pts > reviewed_end:
            raise PreparationError("explicit_bound_exceeds_review_metadata")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%f")
    output = (args.output or EVIDENCE / "analysis" / f"reference-video-replay-input-{stamp}").resolve()
    captures = CAPTURES.resolve()
    if output == captures or not output.is_relative_to(captures):
        raise PreparationError("output_must_be_a_new_directory_below_captures")
    if output.exists():
        raise PreparationError("output_already_exists")
    return video, output, reviewed_end


def installed_decoder_modules():
    # These are existing private packages used by reference-raster-replay.py.
    # No dependencies are downloaded or installed.
    for location in (ROOT / ".research-tools" / "python", ROOT / ".research-tools" / "video-python"):
        if location.is_dir():
            sys.path.insert(0, str(location))
    import av
    from PIL import ImageDraw
    return av, ImageDraw


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def prepare(args: argparse.Namespace) -> dict:
    video, output, reviewed_end = checked_paths(args)
    av, image_draw = installed_decoder_modules()
    started = time.monotonic()
    # mkdir never reuses/deletes an existing output directory.
    output.mkdir(parents=True, exist_ok=False)
    images = output / "frames"
    images.mkdir()
    frames = []
    previous_pts = None
    jpeg_bytes = 0
    with av.open(str(video), mode="r") as container:
        if not container.streams.video:
            raise PreparationError("no_video_stream")
        stream = container.streams.video[0]
        if (stream.codec_context.width, stream.codec_context.height) != (WIDTH, HEIGHT):
            raise PreparationError("unexpected_source_dimensions_no_resizing_permitted")
        if stream.time_base is None:
            raise PreparationError("missing_stream_time_base")
        # Seek to the preceding keyframe; codec preroll is discarded before image
        # conversion/export. No raster outside the exact authorized window is saved.
        seek_tick = int(args.start_pts / Fraction(stream.time_base))
        container.seek(seek_tick, stream=stream, backward=True, any_frame=False)
        for decoded in container.decode(stream):
            if decoded.pts is None or decoded.time_base is None:
                raise PreparationError("missing_actual_frame_pts")
            pts = Fraction(decoded.pts) * Fraction(decoded.time_base)
            if pts < args.start_pts:
                continue
            if pts > args.end_pts:
                break
            if not AUTHORIZED_START <= pts <= AUTHORIZED_END or pts > args.approved_safe_end_pts:
                raise PreparationError("decoded_frame_outside_authorized_window")
            if previous_pts is not None and pts < previous_pts:
                raise PreparationError("nonmonotonic_actual_frame_pts")
            previous_pts = pts
            image = decoded.to_image().convert("RGB")
            try:
                if image.size != (WIDTH, HEIGHT):
                    raise PreparationError("decoded_dimensions_changed_no_resizing_permitted")
                # Match the original private raster replay's normalized waiting-bar mask.
                image_draw.Draw(image).rectangle((WIDTH * MASK_NORMALIZED[0], HEIGHT * MASK_NORMALIZED[1],
                    WIDTH * MASK_NORMALIZED[2], HEIGHT * MASK_NORMALIZED[3]), fill=MASK_RGB)
                index = len(frames)
                relative = f"frames/frame-{index:06d}.jpg"
                destination = output / relative
                image.save(destination, format="JPEG", quality=args.jpeg_quality, subsampling=0)
                pts_ms = round(pts * 1000)
                frames.append({"frame": index, "ptsMs": pts_ms, "elapsedMs": pts_ms, "file": relative})
                jpeg_bytes += destination.stat().st_size
            finally:
                image.close()
            if len(frames) % 100 == 0:
                # Counts/times only: no image, glyph, transcript or private source path.
                print(json.dumps({"preparedFrames": len(frames), "lastPtsMs": frames[-1]["ptsMs"]}), flush=True)
    if not frames:
        raise PreparationError("no_frames_in_authorized_interval")
    if args.expected_frames is not None and len(frames) != args.expected_frames:
        raise PreparationError("actual_frame_count_differs_from_explicit_expectation")
    manifest = {
        "schemaVersion": 1,
        "kind": "reference_video_detector_replay_input",
        "sourceFile": video.name,
        "sourceSha256": sha256(video),
        "width": WIDTH,
        "height": HEIGHT,
        "selectedStartPts": float(args.start_pts),
        "selectedEndPts": float(args.end_pts),
        "approvedSafeEndPts": float(args.approved_safe_end_pts),
        "reviewMetadataEndPts": float(reviewed_end) if reviewed_end is not None else None,
        "overlayMask": {"normalized": list(MASK_NORMALIZED), "rgb": list(MASK_RGB), "raster": "PIL rectangle"},
        "jpegQuality": args.jpeg_quality,
        "jpegSubsampling": 0,
        "frameCount": len(frames),
        "jpegBytes": jpeg_bytes,
        "frames": frames,
    }
    # The final manifest appears only once the complete ordered input is prepared.
    temporary = output / "manifest.pending.json"
    temporary.write_text(json.dumps(manifest, separators=(",", ":")), encoding="utf-8")
    temporary.replace(output / "manifest.json")
    report = {"preparedFrames": len(frames), "firstPtsMs": frames[0]["ptsMs"],
              "lastPtsMs": frames[-1]["ptsMs"], "width": WIDTH, "height": HEIGHT,
              "jpegBytes": jpeg_bytes, "preparationWallSeconds": round(time.monotonic() - started, 3)}
    (output / "preparation.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report


def main() -> int:
    args = arguments()
    try:
        report = prepare(args)
    except Exception as error:
        reason = str(error) if isinstance(error, PreparationError) else type(error).__name__
        print(json.dumps({"preparationError": reason}), file=sys.stderr, flush=True)
        return 1
    print(json.dumps(report), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
