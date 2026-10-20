"""Desktop CPU runner for the full cards.tflite model.

Resize pixels use a half-pixel-center bilinear approximation to Android Canvas
drawBitmap. Android's precise Skia output has not been compared pixel by pixel.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import math
from pathlib import Path
import struct
import time
from typing import Sequence


MODEL_SHA256 = "87493b13b285027b7ee0f727bc9ca5b9a2aec8c7e0e730e43a79417121f3a90c"
RUNTIME_VERSION = "2.2.0"
INPUT_WIDTH = 1280
INPUT_HEIGHT = 640
OUTPUT_SHAPE = (1, 32, 16800)
SCORE_THRESHOLD = 0.55
NMS_IOU_THRESHOLD = 0.45
RESIZE_PARITY = "desktop_bilinear_unverified_against_phone_pixels"
_RANKS = ("A", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE", "TEN", "J", "Q", "K")


def _f32(value: float) -> float:
    return struct.unpack("<f", struct.pack("<f", value))[0]


@dataclass(frozen=True)
class Letterbox:
    scale: float
    width: int
    height: int
    left: int
    top: int


def fit_letterbox(source_width: int, source_height: int,
                  target_width: int = INPUT_WIDTH, target_height: int = INPUT_HEIGHT) -> Letterbox:
    """Match LetterboxGeometry.fit, including Float32 scale and integer margins."""
    if min(source_width, source_height, target_width, target_height) <= 0:
        raise ValueError("image and target dimensions must be positive")
    scale = min(_f32(_f32(target_width) / _f32(source_width)),
                _f32(_f32(target_height) / _f32(source_height)))
    width = max(1, min(target_width, int(_f32(_f32(source_width) * scale))))
    height = max(1, min(target_height, int(_f32(_f32(source_height) * scale))))
    return Letterbox(scale, width, height, (target_width - width) // 2, (target_height - height) // 2)


def normalized_bbox(cx: float, cy: float, width: float, height: float,
                    placement: Letterbox, source_width: int, source_height: int) -> tuple[float, float, float, float] | None:
    """Map model-space center/size to clamped normalized source coordinates."""
    scale, left, top = placement.scale, placement.left, placement.top
    bounds = ((cx - width / 2 - left) / scale / source_width,
              (cy - height / 2 - top) / scale / source_height,
              (cx + width / 2 - left) / scale / source_width,
              (cy + height / 2 - top) / scale / source_height)
    x1, y1, x2, y2 = (min(1.0, max(0.0, v)) for v in bounds)
    if x2 <= x1 or y2 <= y1:
        return None
    return x1, y1, x2, y2


def intersection_over_union(a: Sequence[float], b: Sequence[float]) -> float:
    overlap = max(0.0, min(a[2], b[2]) - max(a[0], b[0])) * max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    area_a = max(0.0, a[2] - a[0]) * max(0.0, a[3] - a[1])
    area_b = max(0.0, b[2] - b[0]) * max(0.0, b[3] - b[1])
    return overlap / max(area_a + area_b - overlap, 0.000001)


def class_color(class_id: int) -> tuple[str, str]:
    if not 0 <= class_id < 28:
        raise ValueError(f"class id outside model range: {class_id}")
    if class_id >= 26:
        return ("BIG" if class_id == 26 else "SMALL"), "NONE"
    return _RANKS[class_id % 13], "RED" if class_id < 13 else "BLACK"


def decode_candidates(values, placement: Letterbox, source_width: int, source_height: int,
                      score_threshold: float = SCORE_THRESHOLD,
                      iou_threshold: float = NMS_IOU_THRESHOLD) -> list[dict]:
    """Decode [1,32,16800] output; stable descending score, class-agnostic NMS."""
    # Accept a flat output buffer or shape-compatible array without importing runtime dependencies.
    rows = values[0] if getattr(values, "ndim", 0) == 3 else values
    n = 16800
    if len(rows) != 32 or len(rows[0]) != n:
        raise ValueError(f"expected output shape [1,32,{n}]")
    if getattr(rows, "ndim", 0) == 2:
        # NumPy path for full model inference: first argmax preserves Android's
        # first-class tie behavior, and flatnonzero preserves anchor order.
        import numpy as np
        class_scores = rows[4:32]
        labels = np.argmax(class_scores, axis=0)
        best_scores = np.maximum(0.0, np.max(class_scores, axis=0))
        candidate_indices = np.flatnonzero(best_scores >= score_threshold)
    else:
        # Plain nested lists remain supported for dependency-free unit tests.
        candidate_indices = []
        labels = [0] * n
        best_scores = [0.0] * n
        for i in range(n):
            for class_id in range(28):
                score = float(rows[class_id + 4][i])
                if score > best_scores[i]:
                    best_scores[i], labels[i] = score, class_id
            if best_scores[i] >= score_threshold:
                candidate_indices.append(i)
    proposals: list[tuple[int, dict]] = []
    for raw_i in candidate_indices:
        i = int(raw_i)
        best_score = float(best_scores[i])
        label = int(labels[i])
        cx = float(rows[0][i]) * INPUT_WIDTH
        cy = float(rows[1][i]) * INPUT_HEIGHT
        bw = float(rows[2][i]) * INPUT_WIDTH
        bh = float(rows[3][i]) * INPUT_HEIGHT
        bbox = normalized_bbox(cx, cy, bw, bh, placement, source_width, source_height)
        if bbox is None:
            continue
        rank, color = class_color(label)
        proposals.append((i, {"rank": rank, "color": color, "conf": best_score,
                              "bbox": bbox, "classId": label}))
    proposals.sort(key=lambda item: -item[1]["conf"])  # Python sort is stable on equal confidence.
    selected: list[dict] = []
    for _, candidate in proposals:
        if all(intersection_over_union(candidate["bbox"], kept["bbox"]) <= iou_threshold for kept in selected):
            selected.append(candidate)
    return selected


def _sample_bilinear(rgb, src_width: int, src_height: int, x: float, y: float) -> tuple[int, int, int]:
    """Sample interleaved uint8 RGB pixels with half-pixel centers and clamped edges."""
    sx = min(src_width - 1.0, max(0.0, x))
    sy = min(src_height - 1.0, max(0.0, y))
    x0, y0 = int(math.floor(sx)), int(math.floor(sy))
    x1, y1 = min(x0 + 1, src_width - 1), min(y0 + 1, src_height - 1)
    fx, fy = sx - x0, sy - y0
    result = []
    for channel in range(3):
        p00 = int(rgb[(y0 * src_width + x0) * 3 + channel])
        p10 = int(rgb[(y0 * src_width + x1) * 3 + channel])
        p01 = int(rgb[(y1 * src_width + x0) * 3 + channel])
        p11 = int(rgb[(y1 * src_width + x1) * 3 + channel])
        result.append(max(0, min(255, int((p00 * (1 - fx) + p10 * fx) * (1 - fy) +
                                         (p01 * (1 - fx) + p11 * fx) * fy + 0.5))))
    return tuple(result)


def resize_rgb(rgb, src_width: int, src_height: int, dst_width: int, dst_height: int,
               mode: str = "bilinear"):
    """Resize packed RGB bytes into an HxWx3 uint8 array; no Pillow resampling involved."""
    import numpy as np
    if mode not in ("bilinear", "nearest"):
        raise ValueError("resize_mode must be 'bilinear' or 'nearest'")
    if len(rgb) != src_width * src_height * 3:
        raise ValueError("RGB buffer length does not match dimensions")
    src = np.frombuffer(rgb, dtype=np.uint8).reshape((src_height, src_width, 3))
    if mode == "nearest":
        ys = np.minimum(src_height - 1, ((np.arange(dst_height) + 0.5) * src_height / dst_height).astype(np.intp))
        xs = np.minimum(src_width - 1, ((np.arange(dst_width) + 0.5) * src_width / dst_width).astype(np.intp))
        return src[ys[:, None], xs[None, :]].copy()
    xs = (np.arange(dst_width, dtype=np.float64) + 0.5) * src_width / dst_width - 0.5
    ys = (np.arange(dst_height, dtype=np.float64) + 0.5) * src_height / dst_height - 0.5
    xs = np.clip(xs, 0, src_width - 1)
    ys = np.clip(ys, 0, src_height - 1)
    x0, y0 = xs.astype(np.intp), ys.astype(np.intp)
    x1, y1 = np.minimum(x0 + 1, src_width - 1), np.minimum(y0 + 1, src_height - 1)
    fx = (xs - x0)[None, :, None]
    fy = (ys - y0)[:, None, None]
    p00 = src[y0[:, None], x0[None, :]].astype(np.float64)
    p10 = src[y0[:, None], x1[None, :]].astype(np.float64)
    p01 = src[y1[:, None], x0[None, :]].astype(np.float64)
    p11 = src[y1[:, None], x1[None, :]].astype(np.float64)
    result = (p00 * (1 - fx) + p10 * fx) * (1 - fy) + (p01 * (1 - fx) + p11 * fx) * fy
    return np.clip(np.floor(result + 0.5), 0, 255).astype(np.uint8)


def preprocess_image(image, resize_mode: str = "bilinear"):
    """Return normalized RGB float32 model input and exact letterbox placement."""
    image = image.convert("RGB")
    sw, sh = image.size
    return preprocess_rgb(image.tobytes(), sw, sh, INPUT_WIDTH, INPUT_HEIGHT, resize_mode)


def preprocess_rgb(rgb, source_width: int, source_height: int, target_width: int,
                   target_height: int, resize_mode: str = "bilinear"):
    """General letterbox helper, also useful for small deterministic parity checks."""
    import numpy as np
    placement = fit_letterbox(source_width, source_height, target_width, target_height)
    resized = resize_rgb(rgb, source_width, source_height, placement.width, placement.height, resize_mode)
    canvas = np.zeros((target_height, target_width, 3), dtype=np.uint8)
    canvas[placement.top:placement.top + placement.height,
           placement.left:placement.left + placement.width] = resized
    return canvas.astype(np.float32) / np.float32(255.0), placement


class FullDetector:
    """Reusable full model runner using the pinned official LiteRT CPU runtime."""
    def __init__(self, model_path: Path, threads: int = 4, resize_mode: str = "bilinear"):
        if resize_mode not in ("bilinear", "nearest"):
            raise ValueError("resize_mode must be 'bilinear' or 'nearest'")
        self.model_path = Path(model_path)
        self.threads = int(threads)
        if self.threads < 1:
            raise ValueError("threads must be positive")
        self.resize_mode = resize_mode
        digest = hashlib.sha256(self.model_path.read_bytes()).hexdigest()
        if digest != MODEL_SHA256:
            raise ValueError(f"model SHA-256 mismatch: {digest}")
        try:
            from ai_edge_litert import __version__ as runtime_version
            from ai_edge_litert.interpreter import Interpreter
        except ImportError as exc:
            raise RuntimeError("LiteRT 2.2.0 is unavailable; activate the prepared desktop runtime") from exc
        if runtime_version != RUNTIME_VERSION:
            raise RuntimeError(f"expected LiteRT {RUNTIME_VERSION}, found {runtime_version}")
        self._interpreter = Interpreter(model_path=str(self.model_path), num_threads=self.threads)
        self._interpreter.allocate_tensors()
        inputs = self._interpreter.get_input_details()
        outputs = self._interpreter.get_output_details()
        if len(inputs) != 1 or tuple(inputs[0]["shape"]) != (1, INPUT_HEIGHT, INPUT_WIDTH, 3):
            raise RuntimeError(f"unexpected model input shape: {[tuple(i['shape']) for i in inputs]}")
        if len(outputs) != 1 or tuple(outputs[0]["shape"]) != OUTPUT_SHAPE:
            raise RuntimeError(f"unexpected model output shape: {[tuple(o['shape']) for o in outputs]}")
        self._input_index = inputs[0]["index"]
        self._output_index = outputs[0]["index"]
        self.metadata = {"runtimeVersion": runtime_version, "modelSha": digest,
                         "resizeParity": RESIZE_PARITY, "resizeMode": resize_mode,
                         "inputShape": (1, INPUT_HEIGHT, INPUT_WIDTH, 3), "outputShape": OUTPUT_SHAPE}

    def predict(self, image) -> dict:
        started = time.perf_counter()
        tensor, placement = preprocess_image(image, self.resize_mode)
        prepared = time.perf_counter()
        self._interpreter.set_tensor(self._input_index, tensor[None, ...])
        self._interpreter.invoke()
        inferred = time.perf_counter()
        output = self._interpreter.get_tensor(self._output_index)
        glyphs = decode_candidates(output, placement, image.width, image.height)
        ended = time.perf_counter()
        return {"glyphs": glyphs, "preprocessMs": (prepared - started) * 1000,
                "inferenceMs": (inferred - prepared) * 1000,
                "decodeMs": (ended - inferred) * 1000,
                "elapsedMs": (ended - started) * 1000, "metadata": self.metadata.copy()}

    def close(self) -> None:
        self._interpreter = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback):
        self.close()
