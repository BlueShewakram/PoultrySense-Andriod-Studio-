"""Tests for the decoupled streaming/detection pipeline primitives.

Run from the repository root:
    python -m unittest discover -s poultrysense_ai/tests -v
"""

import os
import sys
import threading
import time
import unittest

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from stream_pipeline import FrameBuffer, RateGate, normalize_motion


class FrameBufferTests(unittest.TestCase):
    def test_read_before_publish_returns_empty(self):
        buf = FrameBuffer()
        frame, generation = buf.read()
        self.assertIsNone(frame)
        self.assertEqual(generation, 0)

    def test_publish_increments_generation_and_read_returns_latest(self):
        buf = FrameBuffer()
        first = np.zeros((2, 2, 3), dtype=np.uint8)
        second = np.ones((2, 2, 3), dtype=np.uint8)

        self.assertEqual(buf.publish(first), 1)
        self.assertEqual(buf.publish(second), 2)

        frame, generation = buf.read()
        self.assertEqual(generation, 2)
        self.assertTrue(np.array_equal(frame, second))

    def test_publish_drops_intermediate_frames_without_backlog(self):
        buf = FrameBuffer()
        for value in range(5):
            buf.publish(np.full((1, 1, 3), value, dtype=np.uint8))

        frame, generation = buf.read()
        self.assertEqual(generation, 5)
        self.assertEqual(int(frame[0, 0, 0]), 4)

    def test_wait_for_newer_returns_immediately_for_existing_newer_frame(self):
        buf = FrameBuffer()
        buf.publish(np.zeros((1, 1, 3), dtype=np.uint8))

        frame, generation = buf.wait_for_newer(0, timeout=0.1)
        self.assertIsNotNone(frame)
        self.assertEqual(generation, 1)

    def test_wait_for_newer_times_out_without_new_frame(self):
        buf = FrameBuffer()
        buf.publish(np.zeros((1, 1, 3), dtype=np.uint8))

        started = time.monotonic()
        frame, generation = buf.wait_for_newer(1, timeout=0.05)
        elapsed = time.monotonic() - started

        self.assertIsNone(frame)
        self.assertEqual(generation, 1)
        self.assertGreaterEqual(elapsed, 0.04)

    def test_wait_for_newer_wakes_when_frame_is_published(self):
        buf = FrameBuffer()
        buf.publish(np.zeros((1, 1, 3), dtype=np.uint8))
        result = {}

        def waiter():
            result["value"] = buf.wait_for_newer(1, timeout=1.0)

        thread = threading.Thread(target=waiter, daemon=True)
        thread.start()
        time.sleep(0.05)
        buf.publish(np.ones((1, 1, 3), dtype=np.uint8))
        thread.join(timeout=1.0)

        self.assertIn("value", result)
        frame, generation = result["value"]
        self.assertEqual(generation, 2)
        self.assertEqual(int(frame[0, 0, 0]), 1)


class RateGateTests(unittest.TestCase):
    def test_first_attempt_is_always_ready(self):
        gate = RateGate(max_rate_hz=3.0)
        self.assertTrue(gate.ready(now=100.0))

    def test_blocks_inside_the_interval(self):
        gate = RateGate(max_rate_hz=3.0)
        gate.mark(now=100.0)
        self.assertFalse(gate.ready(now=100.1))
        self.assertFalse(gate.ready(now=100.32))

    def test_allows_after_the_interval(self):
        gate = RateGate(max_rate_hz=3.0)
        gate.mark(now=100.0)
        self.assertTrue(gate.ready(now=100.34))

    def test_interval_follows_requested_rate(self):
        gate = RateGate(max_rate_hz=2.0)
        gate.mark(now=0.0)
        self.assertFalse(gate.ready(now=0.49))
        self.assertTrue(gate.ready(now=0.5))

    def test_rejects_non_positive_rate(self):
        with self.assertRaises(ValueError):
            RateGate(max_rate_hz=0.0)


class NormalizeMotionTests(unittest.TestCase):
    def test_matches_30fps_per_frame_displacement(self):
        # 30 px over 0.4 s is 75 px/s, i.e. 2.5 px per 1/30 s frame.
        self.assertAlmostEqual(normalize_motion(30.0, 0.4), 2.5, places=4)

    def test_one_second_window_is_divided_by_thirty(self):
        self.assertAlmostEqual(normalize_motion(30.0, 1.0), 1.0, places=4)

    def test_zero_delta_is_zero(self):
        self.assertEqual(normalize_motion(0.0, 0.4), 0.0)

    def test_tiny_dt_is_clamped(self):
        self.assertGreaterEqual(normalize_motion(30.0, 0.0), 0.0)
        self.assertLess(normalize_motion(30.0, 0.0), 1e6)


if __name__ == "__main__":
    unittest.main()
