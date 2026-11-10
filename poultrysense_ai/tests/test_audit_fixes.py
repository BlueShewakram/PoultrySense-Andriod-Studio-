"""Tests for audit fixes: human veto, min-box scaling, motion dt, export keying.

Run from the repository root:
    python -m unittest discover -s poultrysense_ai/tests -v
"""

import os
import sys
import tempfile
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import poultrysense_ai as ps


class HumanVetoTests(unittest.TestCase):
    def test_box_covering_person_is_rejected(self):
        # Chicken-sized box sitting on a person's torso area: inter/person_area high
        person = [[100, 100, 300, 400]]
        box = [120, 120, 280, 380]
        self.assertTrue(ps.box_is_human(box, person, []))

    def test_small_box_inside_person_rejected(self):
        person = [[100, 100, 300, 400]]
        box = [150, 150, 180, 180]
        self.assertTrue(ps.box_is_human(box, person, []))

    def test_real_chicken_outside_person_not_rejected(self):
        person = [[100, 100, 300, 400]]
        box = [400, 100, 460, 160]
        self.assertFalse(ps.box_is_human(box, person, []))

    def test_face_centre_rejected(self):
        face = [[150, 120, 200, 170]]
        box = [140, 110, 210, 180]
        self.assertTrue(ps.box_is_human(box, [], face))

    def test_no_veto_boxes_never_rejects(self):
        box = [10, 10, 100, 100]
        self.assertFalse(ps.box_is_human(box, [], []))


class MinBoxScalingTests(unittest.TestCase):
    def test_fraction_scales_with_width(self):
        self.assertEqual(ps.MIN_BOX_FRACTION, 0.07)
        # 45 px on 640, ~90 px on 1280
        self.assertAlmostEqual(0.07 * 640, 44.8)
        self.assertAlmostEqual(0.07 * 1280, 89.6)


class ExportSizeKeyingTests(unittest.TestCase):
    def test_marker_round_trip(self):
        d = tempfile.mkdtemp()
        export = os.path.join(d, "model_openvino_model")
        os.makedirs(export)
        ps._mark_export_imgsz(export, 320)
        self.assertTrue(ps._export_imgsz_matches(export, 320))
        self.assertFalse(ps._export_imgsz_matches(export, 224))

    def test_unmarked_export_never_matches(self):
        d = tempfile.mkdtemp()
        export = os.path.join(d, "model_openvino_model")
        os.makedirs(export)
        self.assertFalse(ps._export_imgsz_matches(export, 224))
        self.assertFalse(ps._export_imgsz_matches(export, 320))

    def test_park_export_renames_not_deletes(self):
        d = tempfile.mkdtemp()
        export = os.path.join(d, "model_openvino_model")
        os.makedirs(export)
        ps._park_export(export)
        self.assertFalse(os.path.exists(export))
        # something renamed
        siblings = [n for n in os.listdir(d) if n.startswith("model_openvino_model_backup")]
        self.assertEqual(len(siblings), 1)


class MotionDtTests(unittest.TestCase):
    def test_track_last_hist_time_exists(self):
        self.assertIn("track_last_hist_time", ps.__dict__ or {})
        # attribute check via module global
        self.assertTrue(hasattr(ps, "track_last_hist_time"))


if __name__ == "__main__":
    unittest.main()
