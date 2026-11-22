"""Tests for camera mode negotiation in ThreadedCamera.

Covers the two Windows capture faults that were silently mis-reported:

* ``CAP_PROP_BUFFERSIZE`` is not implemented by the DirectShow backend, so
  ``set()`` returns False and ``get()`` returns -1. The old code assumed the
  request worked.
* ``set(CAP_PROP_FOURCC, MJPG)`` returns True even when the driver keeps the
  native format, so the codec must be verified by reading it back.

Run from the repository root:
    python -m unittest discover -s poultrysense_ai/tests -v
"""

import os
import sys
import unittest

import cv2

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import poultrysense_ai as ps


class FakeCapture:
    """Minimal stand-in for cv2.VideoCapture with driver-like semantics."""

    def __init__(self, fourcc="YUY2", width=640, height=480, fps=30.0,
                 buffer_size=-1.0, auto_exposure=-1.0, accept_mjpg=True):
        self.fourcc = fourcc
        self.width = width
        self.height = height
        self.fps = fps
        self.buffer_size = buffer_size
        self.auto_exposure = auto_exposure
        self.accept_mjpg = accept_mjpg
        self.set_calls = []
        self.grabs = 0

    @staticmethod
    def _fourcc_int(code):
        return sum(ord(ch) << (8 * i) for i, ch in enumerate(code))

    def get(self, prop):
        if prop == cv2.CAP_PROP_FOURCC:
            return float(self._fourcc_int(self.fourcc))
        if prop == cv2.CAP_PROP_FRAME_WIDTH:
            return float(self.width)
        if prop == cv2.CAP_PROP_FRAME_HEIGHT:
            return float(self.height)
        if prop == cv2.CAP_PROP_FPS:
            return float(self.fps)
        if prop == cv2.CAP_PROP_BUFFERSIZE:
            return float(self.buffer_size)
        if prop == cv2.CAP_PROP_AUTO_EXPOSURE:
            return float(self.auto_exposure)
        return -1.0

    def set(self, prop, value):
        self.set_calls.append((prop, value))
        if prop == cv2.CAP_PROP_FOURCC:
            # Windows drivers accept the call but may keep the native format.
            if self.accept_mjpg:
                self.fourcc = "MJPG"
            return True
        if prop == cv2.CAP_PROP_FRAME_WIDTH:
            self.width = int(value)
            return True
        if prop == cv2.CAP_PROP_FRAME_HEIGHT:
            self.height = int(value)
            return True
        if prop == cv2.CAP_PROP_FPS:
            self.fps = float(value)
            return True
        if prop == cv2.CAP_PROP_BUFFERSIZE:
            if self.buffer_size < 0:
                return False          # property unsupported (DSHOW)
            self.buffer_size = float(value)
            return True
        if prop == cv2.CAP_PROP_AUTO_EXPOSURE:
            if self.auto_exposure < 0:
                return False          # property unsupported
            self.auto_exposure = float(value)
            return True
        return False

    def grab(self):
        self.grabs += 1
        return True


class FourCCDecodeTests(unittest.TestCase):
    def test_decodes_yuy2(self):
        self.assertEqual(ps.fourcc_to_str(844715353), "YUY2")

    def test_decodes_mjpg(self):
        self.assertEqual(ps.fourcc_to_str(cv2.VideoWriter_fourcc(*"MJPG")), "MJPG")

    def test_returns_empty_when_unreported(self):
        for value in (0, -1, -466162819, None, "abc"):
            self.assertEqual(ps.fourcc_to_str(value), "", f"value={value!r}")


class ConfigureCaptureTests(unittest.TestCase):
    def test_fourcc_is_requested_before_resolution(self):
        cap = FakeCapture()
        ps.configure_capture(cap, 640, 480, 30, want_mjpg=True)

        props = [prop for prop, _ in cap.set_calls]
        self.assertLess(
            props.index(cv2.CAP_PROP_FOURCC),
            props.index(cv2.CAP_PROP_FRAME_WIDTH),
            "FOURCC must be negotiated before width/height",
        )

    def test_reports_mjpg_when_driver_accepts(self):
        cap = FakeCapture(accept_mjpg=True)
        mode = ps.configure_capture(cap, 640, 480, 30, want_mjpg=True)

        self.assertTrue(mode["mjpg_requested"])
        self.assertTrue(mode["mjpg_applied"])
        self.assertEqual(mode["codec"], "MJPG")

    def test_detects_refused_mjpg_instead_of_trusting_set(self):
        """set(FOURCC) returns True on Windows even when MJPG is not applied."""
        cap = FakeCapture(accept_mjpg=False)
        mode = ps.configure_capture(cap, 640, 480, 30, want_mjpg=True)

        self.assertTrue(mode["mjpg_requested"])
        self.assertFalse(mode["mjpg_applied"])
        self.assertEqual(mode["codec"], "YUY2")

    def test_skips_mjpg_request_when_disabled(self):
        cap = FakeCapture()
        mode = ps.configure_capture(cap, 640, 480, 30, want_mjpg=False)

        self.assertNotIn(cv2.CAP_PROP_FOURCC, [prop for prop, _ in cap.set_calls])
        self.assertFalse(mode["mjpg_requested"])

    def test_buffer_control_detected_when_supported(self):
        cap = FakeCapture(buffer_size=1.0)
        mode = ps.configure_capture(cap, 640, 480, 30)

        self.assertTrue(mode["buffer_control"])

    def test_buffer_control_reported_missing_on_dshow(self):
        cap = FakeCapture(buffer_size=-1.0)
        mode = ps.configure_capture(cap, 640, 480, 30)

        self.assertFalse(mode["buffer_control"])

    def test_auto_exposure_not_claimed_when_unsupported(self):
        cap = FakeCapture(auto_exposure=-1.0)
        mode = ps.configure_capture(cap, 640, 480, 30)

        self.assertIsNone(mode["auto_exposure"])

    def test_auto_exposure_enabled_when_supported(self):
        cap = FakeCapture(auto_exposure=0.75)
        mode = ps.configure_capture(cap, 640, 480, 30)

        self.assertAlmostEqual(mode["auto_exposure"], 0.25)

    def test_flushes_frames_queued_during_negotiation(self):
        cap = FakeCapture()
        ps.configure_capture(cap, 640, 480, 30)

        self.assertGreaterEqual(cap.grabs, 3)


class BackoffTests(unittest.TestCase):
    def test_backoff_doubles(self):
        self.assertAlmostEqual(ps.next_backoff(0.25), 0.5)

    def test_backoff_is_capped(self):
        self.assertAlmostEqual(ps.next_backoff(4.0, maximum=5.0), 5.0)
        self.assertAlmostEqual(ps.next_backoff(100.0, maximum=5.0), 5.0)

    def test_backoff_never_returns_zero(self):
        self.assertGreater(ps.next_backoff(0.0), 0.0)


if __name__ == "__main__":
    unittest.main()
