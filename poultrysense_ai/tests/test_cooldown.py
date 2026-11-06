"""Tests for per-chicken Gemini cooldowns and scan-scoped evidence reset.

Run from the repository root:
    python -m unittest discover -s poultrysense_ai/tests -v
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import poultrysense_ai as ps


class CooldownTests(unittest.TestCase):
    def setUp(self):
        ps.reset_global_cooldown()

    def tearDown(self):
        ps.reset_global_cooldown()

    def test_one_bird_cooling_down_does_not_block_another(self):
        ps.record_track_alert(1, timestamp=1_000.0)

        self.assertTrue(ps.is_track_cooldown_active(1, curr_time=1_001.0))
        self.assertFalse(ps.is_track_cooldown_active(2, curr_time=1_001.0))

    def test_per_bird_cooldown_expires_after_its_window(self):
        ps.record_track_alert(1, timestamp=1_000.0)
        expired = 1_000.0 + ps.GLOBAL_ALERT_COOLDOWN_SEC + 1.0

        self.assertFalse(ps.is_track_cooldown_active(1, curr_time=expired))

    def test_system_wide_gap_blocks_immediate_calls_only(self):
        ps.record_track_alert(1, timestamp=1_000.0)

        self.assertTrue(
            ps.is_global_cooldown_active(curr_time=1_000.0 + ps.GEMINI_MIN_QUERY_GAP_SEC - 0.5)
        )
        self.assertFalse(
            ps.is_global_cooldown_active(curr_time=1_000.0 + ps.GEMINI_MIN_QUERY_GAP_SEC + 0.5)
        )

    def test_remaining_seconds_are_reported_per_bird(self):
        ps.record_track_alert(1, timestamp=1_000.0)
        remaining = ps.get_track_cooldown_remaining(1, curr_time=1_010.0)

        self.assertAlmostEqual(remaining, ps.GLOBAL_ALERT_COOLDOWN_SEC - 10.0, places=3)
        self.assertEqual(ps.get_track_cooldown_remaining(2, curr_time=1_010.0), 0.0)

    def test_reset_clears_per_bird_and_system_wide_windows(self):
        ps.record_track_alert(1, timestamp=1_000.0)
        ps.reset_global_cooldown()

        self.assertFalse(ps.is_track_cooldown_active(1, curr_time=1_001.0))
        self.assertFalse(ps.is_global_cooldown_active(curr_time=1_001.0))


class ScanStartResetTests(unittest.TestCase):
    def setUp(self):
        ps.reset_global_cooldown()
        ps.behavior_history.records.clear()
        ps.behavior_history.baselines.clear()
        ps.track_histories.clear()
        ps.track_motion_windows.clear()
        ps.track_stationary_duration.clear()
        ps.track_classifications.clear()
        ps.track_first_seen.clear()
        ps.track_display_ids.clear()

    def test_scan_start_clears_evidence_but_keeps_identity(self):
        ps.track_first_seen[3] = 111.0
        ps.track_display_ids[77] = 3
        ps.behavior_history.records[3].append((1.0, "walk", 2.0, False, (10, 10)))
        ps.behavior_history.baselines[3] = {"walk_pct": 50.0}
        ps.track_histories[3].append(1)
        ps.track_motion_windows[3].append(1.0)
        ps.track_stationary_duration[3] = 12.0
        ps.track_classifications[3] = {"behavior": "rest", "unhealthy_prob": 0.1}

        ps.scan_start()

        # Identity survives.
        self.assertEqual(ps.track_first_seen.get(3), 111.0)
        self.assertEqual(ps.track_display_ids.get(77), 3)

        # Evidence does not.
        self.assertEqual(len(ps.behavior_history.records[3]), 0)
        self.assertNotIn(3, ps.behavior_history.baselines)
        self.assertEqual(len(ps.track_histories[3]), 0)
        self.assertEqual(len(ps.track_motion_windows[3]), 0)
        self.assertNotIn(3, ps.track_stationary_duration)
        self.assertNotIn(3, ps.track_classifications)


if __name__ == "__main__":
    unittest.main()
