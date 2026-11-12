"""Tests for safe OpenVINO fallback behaviour.

Covers the failure that took the detector offline when INT8 was enabled:
Ultralytics needs ``nncf`` for OpenVINO INT8, and without it the export raised,
the app fell back to the PyTorch ``.pt``, and ``device='intel:gpu'`` then made
every ``track()`` call fail. A failed export must never destroy the working
FP32 export either.

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
    def mkdtemp(self):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        return d

    def make_export(self, marker):
        """Create a fake *_openvino_model dir with the given marker text."""
        d = self.mkdtemp()
        export = os.path.join(d, "model_openvino_model")
        os.makedirs(export)
        with open(ps._imgsz_marker_path(export), "w", encoding="utf-8") as fh:
            fh.write(marker)
        with open(os.path.join(d, "model.pt"), "wb") as fh:
            fh.write(b"fake")
        return d, export, os.path.join(d, "model.pt")


class NncfGuardTests(unittest.TestCase):
    def test_nncf_available_returns_bool(self):
        self.assertIsInstance(ps._nncf_available(), bool)

    def test_int8_degrades_to_fp32_when_nncf_missing(self):
        kwargs, effective = ps._openvino_export_kwargs(
            480, "int8", "calib.yaml", nncf_ok=False
        )
        self.assertEqual(effective, "fp32")
        self.assertNotIn("data", kwargs)
        self.assertNotIn("quantize", kwargs)
        self.assertNotIn("int8", kwargs)

    def test_int8_uses_quantize_when_nncf_present(self):
        kwargs, effective = ps._openvino_export_kwargs(
            480, "int8", "calib.yaml", nncf_ok=True
        )
        self.assertEqual(effective, "int8")
        self.assertEqual(kwargs["data"], "calib.yaml")
        self.assertTrue("quantize" in kwargs or "int8" in kwargs)

    def test_fp32_stays_fp32_even_with_nncf(self):
        kwargs, effective = ps._openvino_export_kwargs(
            480, "fp32", "calib.yaml", nncf_ok=True
        )
        self.assertEqual(effective, "fp32")
        self.assertNotIn("quantize", kwargs)


class CachedExportTests(_TempDirTestCase):
    def test_cached_fp32_export_reused_when_int8_unavailable(self):
        """INT8 requested without nncf must reuse FP32, not park it."""
        _, export, pt = self.make_export("480:fp32")
        orig_nncf = ps._nncf_available
        ps._nncf_available = lambda: False
        self.addCleanup(setattr, ps, "_nncf_available", orig_nncf)
        orig_calib = ps._resolve_calibration_data
        ps._resolve_calibration_data = lambda *a, **k: None
        self.addCleanup(setattr, ps, "_resolve_calibration_data", orig_calib)

        resolved = ps.resolve_openvino(pt, imgsz=480, precision="int8")

        self.assertEqual(os.path.normpath(resolved), os.path.normpath(export))
        self.assertTrue(os.path.isdir(export), "cached FP32 export was parked")
        self.assertEqual(
            [n for n in os.listdir(os.path.dirname(export)) if "_backup" in n], []
        )


class ExportFailureRecoveryTests(_TempDirTestCase):
    def test_failed_export_restores_parked_export(self):
        """A raised export must leave the previous working export in place."""
        _, export, pt = self.make_export("640:fp32")
        sentinel = os.path.join(export, "previous-export.bin")
        with open(sentinel, "wb") as fh:
            fh.write(b"previous-export")

        import ultralytics

        class _FailingYOLO:
            def __init__(self, *args, **kwargs):
                pass

            def export(self, **kwargs):
                raise RuntimeError("export exploded")

        orig_yolo = ultralytics.YOLO
        ultralytics.YOLO = _FailingYOLO
        self.addCleanup(setattr, ultralytics, "YOLO", orig_yolo)

        resolved = ps.resolve_openvino(pt, imgsz=480, precision="fp32")

        self.assertEqual(os.path.normpath(resolved), os.path.normpath(pt))
        self.assertTrue(os.path.isdir(export), "previous export was not restored")
        self.assertTrue(
            os.path.isfile(sentinel), "restored export lost its original contents"
        )


class TrackDeviceTests(unittest.TestCase):
    def test_intel_device_maps_to_cpu_for_pytorch_model(self):
        self.assertEqual(ps._track_device("intel:gpu", "models/model.pt"), "cpu")

    def test_intel_device_is_kept_for_openvino_export(self):
        self.assertEqual(
            ps._track_device("intel:gpu", "models/model_openvino_model"),
            "intel:gpu",
        )

    def test_intel_device_maps_to_cpu_for_onnx(self):
        self.assertEqual(ps._track_device("intel:gpu", "models/model.onnx"), "cpu")

    def test_cuda_device_is_kept(self):
        self.assertEqual(ps._track_device("cuda:0", "models/model.pt"), "cuda:0")

    def test_cpu_stays_cpu(self):
        self.assertEqual(ps._track_device("cpu", "models/model.pt"), "cpu")


if __name__ == "__main__":
    unittest.main()
