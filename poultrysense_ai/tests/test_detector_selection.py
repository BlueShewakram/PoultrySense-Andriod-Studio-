import importlib.util
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

spec = importlib.util.spec_from_file_location("poultrysense_ai", ROOT / "poultrysense_ai.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DetectorSelectionTests(unittest.TestCase):
    def test_custom_model_takes_priority(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            custom_model = base / "chicken_detector.pt"
            custom_model.write_bytes(b"fake-model")
            os.environ["POULTRYSENSE_MODEL_PATH"] = str(custom_model)
            try:
                cfg = module.resolve_detector_model(base_dir=base)
                self.assertEqual(cfg["model_path"], str(custom_model))
                self.assertEqual(cfg["model_kind"], "custom_poultry_detector")
                self.assertEqual(cfg["detect_classes"], [0, 14])
            finally:
                os.environ.pop("POULTRYSENSE_MODEL_PATH", None)

    def test_fallback_model_is_used_when_no_custom_model_exists(self):
        os.environ.pop("POULTRYSENSE_MODEL_PATH", None)
        cfg = module.resolve_detector_model(base_dir=Path("/tmp"))
        self.assertEqual(cfg["model_kind"], "fallback_yolo11n")
        self.assertEqual(cfg["detect_classes"], [14])
        self.assertIn("yolo11n.pt", cfg["model_path"])


if __name__ == "__main__":
    import tempfile
    unittest.main()
