"""Tests for the opt-in INT8 detector export path.

Covers precision-keyed export markers, export-kwarg construction (including the
no-calibration fallback to FP32), and calibration dataset resolution.

Run from the repository root:
    python -m unittest discover -s poultrysense_ai/tests -v
"""

import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import poultrysense_ai as ps


class _TempDirTestCase(unittest.TestCase):
    """Base class that deletes every temp directory it creates."""

    def mkdtemp(self):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        return d


class PrecisionMarkerTests(_TempDirTestCase):
    def make_export_dir(self):
        d = self.mkdtemp()
        export = os.path.join(d, "model_openvino_model")
        os.makedirs(export)
        return export

    def test_int8_marker_matches_only_int8(self):
        export = self.make_export_dir()
        ps._mark_export(export, 480, "int8")
        self.assertTrue(ps._export_matches(export, 480, "int8"))
        self.assertFalse(ps._export_matches(export, 480, "fp32"))

    def test_fp32_marker_matches_only_fp32(self):
        export = self.make_export_dir()
        ps._mark_export(export, 480, "fp32")
        self.assertTrue(ps._export_matches(export, 480, "fp32"))
        self.assertFalse(ps._export_matches(export, 480, "int8"))

    def test_marker_is_keyed_by_size_too(self):
        export = self.make_export_dir()
        ps._mark_export(export, 480, "int8")
        self.assertFalse(ps._export_matches(export, 640, "int8"))

    def test_legacy_size_only_marker_is_treated_as_fp32(self):
        export = self.make_export_dir()
        with open(ps._imgsz_marker_path(export), "w", encoding="utf-8") as fh:
            fh.write("320")
        self.assertTrue(ps._export_matches(export, 320, "fp32"))
        self.assertFalse(ps._export_matches(export, 320, "int8"))

    def test_unmarked_export_never_matches(self):
        export = self.make_export_dir()
        self.assertFalse(ps._export_matches(export, 480, "fp32"))
        self.assertFalse(ps._export_matches(export, 480, "int8"))


class ExportKwargsTests(unittest.TestCase):
    def test_fp32_never_requests_calibration(self):
        kwargs, effective = ps._openvino_export_kwargs(480, "fp32", "ignored.yaml")
        self.assertEqual(effective, "fp32")
        self.assertNotIn("data", kwargs)
        self.assertNotIn("quantize", kwargs)
        self.assertNotIn("int8", kwargs)

    def test_int8_without_calibration_falls_back_to_fp32(self):
        kwargs, effective = ps._openvino_export_kwargs(480, "int8", None)
        self.assertEqual(effective, "fp32")
        self.assertNotIn("data", kwargs)
        self.assertNotIn("quantize", kwargs)
        self.assertNotIn("int8", kwargs)

    def test_int8_with_calibration_requests_quantization(self):
        kwargs, effective = ps._openvino_export_kwargs(480, "int8", "calib.yaml")
        self.assertEqual(effective, "int8")
        self.assertEqual(kwargs["data"], "calib.yaml")
        # Ultralytics 8.4+ uses quantize=8; older releases only know int8=True.
        self.assertTrue("quantize" in kwargs or "int8" in kwargs)
        if "quantize" in kwargs:
            self.assertEqual(kwargs["quantize"], 8)
        else:
            self.assertTrue(kwargs["int8"])


class CalibrationResolutionTests(_TempDirTestCase):
    def test_explicit_yaml_wins(self):
        d = self.mkdtemp()
        yaml_path = os.path.join(d, "data.yaml")
        with open(yaml_path, "w", encoding="utf-8") as fh:
            fh.write("val: .\n")
        resolved = ps._resolve_calibration_data(
            calib_data=yaml_path, calib_dir="", bundled=""
        )
        self.assertEqual(resolved, yaml_path)

    def test_missing_explicit_yaml_is_not_faked(self):
        resolved = ps._resolve_calibration_data(
            calib_data=os.path.join(self.mkdtemp(), "does-not-exist.yaml"),
            calib_dir="",
            bundled="",
        )
        self.assertIsNone(resolved)

    def test_image_dir_is_wrapped_in_a_dataset_yaml(self):
        image_dir = self.mkdtemp()
        resolved = ps._resolve_calibration_data(
            calib_data="", calib_dir=image_dir, bundled=""
        )
        self.assertIsNotNone(resolved)
        try:
            with open(resolved, "r", encoding="utf-8") as fh:
                text = fh.read()
            self.assertIn("names:", text)
            self.assertIn("val:", text)
            self.assertIn(
                os.path.abspath(image_dir).replace("\\", "/"),
                text.replace("\\", "/"),
            )
        finally:
            os.remove(resolved)

    def test_bundled_dataset_is_the_last_resort(self):
        d = self.mkdtemp()
        bundled = os.path.join(d, "data.yaml")
        with open(bundled, "w", encoding="utf-8") as fh:
            fh.write("val: .\n")
        resolved = ps._resolve_calibration_data(
            calib_data="", calib_dir="", bundled=bundled
        )
        self.assertEqual(resolved, bundled)

    def test_nothing_available_returns_none(self):
        resolved = ps._resolve_calibration_data(
            calib_data="", calib_dir="", bundled=""
        )
        self.assertIsNone(resolved)


if __name__ == "__main__":
    unittest.main()
