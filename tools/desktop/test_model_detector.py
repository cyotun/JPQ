"""Pure geometry and preprocessing tests; intentionally do not import LiteRT."""
import unittest

from model_detector import (
    Letterbox, _sample_bilinear, class_color, decode_candidates, fit_letterbox,
    intersection_over_union, normalized_bbox, preprocess_rgb,
)


class LetterboxTests(unittest.TestCase):
    def test_non_integer_scale_floors_raster_size_and_centers_integer_margins(self):
        # 7x3 into 10x10 gives scale 10/7, a floored 10x4 raster and 3 px top margin.
        fitted = fit_letterbox(7, 3, 10, 10)
        self.assertEqual(fitted, Letterbox(1.4285714626312256, 10, 4, 0, 3))

    def test_rgb_channels_and_black_padding(self):
        # A 2x1 red/blue source placed in 4x4 leaves one black row on each side.
        tensor, placement = preprocess_rgb(bytes((255, 0, 0, 0, 0, 255)), 2, 1, 4, 4, "nearest")
        self.assertEqual((placement.width, placement.height, placement.left, placement.top), (4, 2, 0, 1))
        self.assertEqual(tuple(tensor[0, 0]), (0.0, 0.0, 0.0))
        self.assertEqual(tuple(tensor[1, 0]), (1.0, 0.0, 0.0))
        self.assertEqual(tuple(tensor[1, 3]), (0.0, 0.0, 1.0))

    def test_bilinear_uses_half_pixel_centers_and_preserves_rgb_order(self):
        # Center between two pixels is their channel-wise midpoint.
        rgb = bytes((0, 20, 100, 200, 60, 0))
        self.assertEqual(_sample_bilinear(rgb, 2, 1, 0.5, 0), (100, 40, 50))

    def test_bbox_clamps_to_normalized_source_edges_and_rejects_empty(self):
        placement = fit_letterbox(10, 10, 10, 10)
        self.assertEqual(normalized_bbox(0, 0, 4, 4, placement, 10, 10), (0.0, 0.0, 0.2, 0.2))
        self.assertIsNone(normalized_bbox(-4, 5, 2, 2, placement, 10, 10))


class DecodeSupportTests(unittest.TestCase):
    def test_class_color_mapping(self):
        self.assertEqual(class_color(0), ("A", "RED"))
        self.assertEqual(class_color(1), ("TWO", "RED"))
        self.assertEqual(class_color(14), ("TWO", "BLACK"))
        self.assertEqual(class_color(13), ("A", "BLACK"))
        self.assertEqual(class_color(26), ("BIG", "NONE"))
        self.assertEqual(class_color(27), ("SMALL", "NONE"))

    def test_iou_is_class_agnostic_geometry(self):
        self.assertAlmostEqual(intersection_over_union((0, 0, 1, 1), (0.5, 0, 1.5, 1)), 1 / 3)
        self.assertEqual(intersection_over_union((0, 0, 1, 1), (1, 0, 2, 1)), 0.0)

    def test_score_boundaries_match_android_inclusive_threshold(self):
        rows = [[0.0] * 16800 for _ in range(32)]
        # Three distant boxes: exactly-at-threshold is kept; just below is dropped.
        for i, x in ((0, 2.0), (1, 5.0), (2, 8.0)):
            rows[0][i], rows[1][i] = x / 1280, 5 / 640
            rows[2][i], rows[3][i] = 1 / 1280, 2 / 640
        rows[4][0], rows[4][1], rows[4][2] = 0.55, 0.549999, 0.70
        glyphs = decode_candidates(rows, Letterbox(1.0, 10, 10, 0, 0), 10, 10)
        self.assertEqual([g["conf"] for g in glyphs], [0.70, 0.55])

    def test_nms_suppresses_overlapping_different_classes_and_keeps_stable_ties(self):
        rows = [[0.0] * 16800 for _ in range(32)]
        # Anchors 0 and 1 overlap exactly but have different classes; anchor 2 is separate.
        for i, x in ((0, 3.0), (1, 3.0), (2, 8.0)):
            rows[0][i], rows[1][i] = x / 1280, 5 / 640
            rows[2][i], rows[3][i] = 2 / 1280, 4 / 640
        rows[4][0], rows[17][1], rows[4][2] = 0.9, 0.9, 0.9
        glyphs = decode_candidates(rows, Letterbox(1.0, 10, 10, 0, 0), 10, 10)
        self.assertEqual([g["classId"] for g in glyphs], [0, 0])
        self.assertNotEqual(glyphs[0]["bbox"], glyphs[1]["bbox"])


if __name__ == "__main__":
    unittest.main()
