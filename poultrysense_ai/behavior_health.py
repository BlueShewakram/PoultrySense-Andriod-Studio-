"""
Behavior / posture health scoring for live flock monitoring.

Health is inferred from motion + bounding-box geometry (not fecal imagery):
  - NORMAL            : upright-ish posture and/or recent movement
  - LETHARGIC         : little movement over a window (possible illness)
  - ABNORMAL_POSTURE  : lying / sprawled geometry relative to recent baseline

Maps to app-facing HEALTHY / ILL for Android compatibility.
"""

from __future__ import annotations

import math
import time
from collections import defaultdict, deque
from typing import Deque, Dict, List, Optional, Tuple


BBox = List[float]  # [x1, y1, x2, y2]


def _bbox_metrics(bbox: BBox) -> Tuple[float, float, float, float, float, float]:
    x1, y1, x2, y2 = bbox
    w = max(1.0, float(x2 - x1))
    h = max(1.0, float(y2 - y1))
    cx = (x1 + x2) * 0.5
    cy = (y1 + y2) * 0.5
    aspect = w / h  # >1 = wider than tall (often lying / sitting from side/oblique view)
    area = w * h
    return cx, cy, w, h, aspect, area


class BehaviorHealthEngine:
    def __init__(
        self,
        history_sec: float = 8.0,
        lethargy_speed: float = 0.012,  # fraction of frame diagonal / sec
        lying_aspect: float = 1.35,  # w/h threshold for abnormal posture
        min_samples: int = 8,
    ):
        self.history_sec = history_sec
        self.lethargy_speed = lethargy_speed
        self.lying_aspect = lying_aspect
        self.min_samples = min_samples
        # track_id -> deque[(t, cx, cy, aspect, area)]
        self._hist: Dict[int, Deque[Tuple[float, float, float, float, float]]] = defaultdict(deque)

    def reset(self):
        self._hist.clear()

    def observe(self, track_id: int, bbox: BBox, frame_shape, now: Optional[float] = None) -> dict:
        now = time.time() if now is None else now
        h_frame, w_frame = frame_shape[:2]
        diag = math.hypot(float(w_frame), float(h_frame)) + 1e-6
        cx, cy, _w, _h, aspect, area = _bbox_metrics(bbox)

        q = self._hist[track_id]
        q.append((now, cx, cy, aspect, area))
        while q and (now - q[0][0]) > self.history_sec:
            q.popleft()

        behavior = "normal"
        status = "HEALTHY"
        confidence = 0.55
        speed = 0.0
        mean_aspect = aspect

        if len(q) >= self.min_samples:
            t0, x0, y0, *_ = q[0]
            t1, x1, y1, *_ = q[-1]
            dt = max(1e-3, t1 - t0)
            speed = math.hypot(x1 - x0, y1 - y0) / dt / diag
            mean_aspect = sum(s[3] for s in q) / len(q)

            # Prolonged low motion → lethargic
            if speed < self.lethargy_speed:
                behavior = "lethargic"
                status = "ILL"
                confidence = min(0.95, 0.60 + (self.lethargy_speed - speed) * 20.0)

            # Wide bbox relative to height → likely lying / sprawled
            if mean_aspect >= self.lying_aspect:
                # Lying + still is stronger signal than lying alone
                if speed < self.lethargy_speed * 2.5:
                    behavior = "abnormal_posture"
                    status = "ILL"
                    confidence = min(0.97, 0.65 + (mean_aspect - self.lying_aspect) * 0.4)
                elif behavior == "normal":
                    behavior = "abnormal_posture"
                    status = "ILL"
                    confidence = 0.7

            if behavior == "normal":
                confidence = min(0.93, 0.55 + speed * 15.0)

        return {
            "behavior": behavior,  # normal | lethargic | abnormal_posture
            "status": status,  # HEALTHY | ILL
            "confidence": round(float(confidence), 3),
            "speed": round(float(speed), 4),
            "aspect": round(float(mean_aspect), 3),
        }

    def flock_health_index(self, per_track: List[dict]) -> int:
        if not per_track:
            return 95
        healthy = sum(1 for t in per_track if t.get("status") == "HEALTHY")
        return int(round(100.0 * healthy / len(per_track)))
