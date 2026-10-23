"""Pixel-level verification of emulator task capture under actual visible overlays.

Uses the bundled Python runtime (Pillow/numpy). No video replay or recognition score
is involved. Inputs are the unedited physical screenshots and CaptureFrameProbe PNGs.
"""
from __future__ import annotations

import argparse
import json
import re
import zipfile
from pathlib import Path

import numpy as np
from PIL import Image


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def rgb(path: Path):
    return np.asarray(Image.open(path).convert("RGB"))


def verify(run: Path, reference: Path, notification_panel: bool):
    summary = read_json(run / "summary.json")
    assert summary["completed"] and summary["appStopped"]
    assert summary["sdkInt"] == 37 and len(summary["stages"]) == 3
    baseline = rgb(reference)
    height, width, _ = baseline.shape
    # Test exact solid interiors, excluding renderer-dependent glyph/shape edges.
    # This is not an assertion that two independent GPU renderings are byte-identical.
    colours = ((0, 255, 0), (0, 0, 255), (255, 153, 0))
    static = np.zeros((height, width), dtype=bool)
    for colour in colours:
        interior = np.all(baseline == colour, axis=2)
        # Three-pixel erosion excludes fractional marker edge rasterization.
        for axis in (0, 1):
            for shift in (-3, -2, -1, 1, 2, 3):
                interior &= np.all(np.roll(baseline, shift, axis=axis) == colour, axis=2)
        static |= interior
    static[:int(height * .16), :] = False
    static[int(height * .66):int(height * .72), :] = False
    static[int(height * .85):, :] = False
    assert static.sum() > width * height * .5, "Reference must contain the unobstructed solid fixture"
    evidence = run / "run" / "evidence"
    archive = run / "run" / "diagnostics.zip"
    # Extract only the named probe files, with no archive-controlled destination paths.
    with zipfile.ZipFile(archive) as zipped:
        for stage in summary["stages"]:
            probe_id = stage["probeId"]
            assert re.fullmatch(r"\d+-[0-9a-f]{8}", probe_id)
            folder = evidence / "capture-probes" / probe_id
            folder.mkdir(parents=True, exist_ok=True)
            for name in ("converted.png", "legacy.png", "probe.json"):
                (folder / name).write_bytes(zipped.read(f"capture-probes/{probe_id}/{name}"))
    results = []
    timestamps = []
    counters = []
    for stage in summary["stages"]:
        name = stage["stage"]
        folder = evidence / "capture-probes" / stage["probeId"]
        captured = rgb(folder / "converted.png")
        physical = rgb(run / f"{name}-physical.png")
        probe = read_json(folder / "probe.json")
        assert captured.shape == physical.shape == baseline.shape
        timestamp = stage["imageTimestampNs"]
        assert timestamp == probe["imageTimestampNs"]
        assert timestamp >= stage["stageSignalMonotonicNs"]
        timestamps.append(timestamp)
        assert np.array_equal(captured, rgb(folder / "legacy.png"))
        counter = 0
        for bit in range(16):
            value = captured[int(height * .69), int(width * (.125 + bit * .05))]
            assert np.array_equal(value, (0, 0, 0)) or np.array_equal(value, (255, 255, 0))
            if value[0] == 255:
                counter |= 1 << bit
        counters.append(counter)
        mismatch = np.any(captured != baseline, axis=2) & static
        assert not mismatch.any(), f"{name}: underlying static fixture pixels changed"
        physical_changed = np.any(physical != baseline, axis=2) & static
        changed_ratio = float(physical_changed.sum() / static.sum())
        assert changed_ratio > .01, f"{name}: overlay must actually be visible"
        projection = (run / f"{name}-projection.txt").read_text(encoding="utf-8-sig")
        assert re.search(r"packageName:\s*com\.cyotun\.jpq\.candidate\b", projection)
        assert "RECORD_CONTENT_TASK" in projection
        target = re.search(r"targetUid\s*=\s*(\d+)", projection)
        token = re.search(r"tokenToRecord\s*=\s*([^\r\n]+)", projection)
        assert target and token and "com.cyotun.jpq.tests" in token[1]
        assert f"A={target[1]}:com.cyotun.jpq.tests" in token[1]
        if name == "other":
            magenta = ((physical[:, :, 0] > 150) & (physical[:, :, 2] > 150)
                       & (physical[:, :, 1] < 100))
            assert magenta.sum() > 10000, "Independent overlay not visible"
            assert not (((captured[:, :, 0] > 150) & (captured[:, :, 2] > 150)
                         & (captured[:, :, 1] < 100))).any()
        if name == "notification" and notification_panel:
            assert changed_ratio > .5, "System notification panel not physically covering the fixture"
            windows = (run / f"{name}-windows.txt").read_text(encoding="utf-8-sig")
            assert "NotificationShade" in windows
        results.append({"stage": name, "probeId": stage["probeId"],
                        "solidFixturePixelsCompared": int(static.sum()),
                        "capturedStaticMismatchPixels": int(mismatch.sum()),
                        "physicalChangedRatio": changed_ratio,
                        "imageTimestampNs": timestamp,
                        "liveFixtureCounter": counter,
                        "freshAfterStageSignal": True,
                        "rawConversionMismatchPixels": 0})
    assert timestamps == sorted(set(timestamps)), "Fresh distinct frame timestamps required"
    assert len(set(counters)) == 3, "Underlying dynamic fixture must advance across all stages"
    assert re.search(r"Media Projection:\s*null", (run / "stopped-projection.txt").read_text(encoding="utf-8-sig"))
    assert "OK (1 test)" in (run / "instrumentation.txt").read_text(encoding="utf-8-sig")
    return {"passed": True, "scope": "emulator_capture_method_only_not_game_accuracy",
            "runId": summary["runId"], "notificationPanelConfirmed": notification_panel,
            "headsUpBannerConfirmed": False,
            "pixelComparison": "exact solid fixture interiors; excludes dynamic text/stripe, bottom controls and antialiased boundaries",
            "stages": results}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument("reference", type=Path)
    parser.add_argument("--notification-panel", action="store_true")
    args = parser.parse_args()
    report = verify(args.run.resolve(), args.reference.resolve(), args.notification_panel)
    (args.run / "pixel-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
