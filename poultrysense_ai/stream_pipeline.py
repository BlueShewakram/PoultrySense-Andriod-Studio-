"""Streaming primitives that keep camera preview and YOLO detection decoupled.

The camera preview must never wait for inference. A single producer publishes
the newest frame into :class:`FrameBuffer`; the detection loop only ever reads
the latest frame, so slow inference can never build a backlog of stale frames.
"""

import threading
import time

# The existing behaviour thresholds were tuned on 30 FPS frame-to-frame
# displacement. Detection now runs at 2-3 Hz, so motion is normalised back to
# its 30 FPS equivalent before being compared against those thresholds.
REFERENCE_FPS = 30.0
_MIN_DT = 1e-3


class FrameBuffer:
    """Thread-safe holder for the single most recent camera frame.

    ``publish`` replaces the previous frame instead of queueing it. Consumers
    therefore always receive the freshest frame and can never fall behind.
    """

    def __init__(self):
        self._condition = threading.Condition()
        self._frame = None
        self._generation = 0

    @property
    def generation(self):
        with self._condition:
            return self._generation

    def publish(self, frame):
        """Store ``frame`` as the latest frame and wake waiting consumers."""
        with self._condition:
            self._frame = frame
            self._generation += 1
            self._condition.notify_all()
            return self._generation

    def read(self):
        """Return ``(frame, generation)`` without blocking.

        The frame is ``None`` before the first publish. The returned frame is
        the producer's array, so consumers that draw on it must copy first.
        """
        with self._condition:
            return self._frame, self._generation

    def wait_for_newer(self, last_generation, timeout=None):
        """Block until a frame newer than ``last_generation`` is published.

        Returns ``(frame, generation)`` or ``(None, current_generation)`` when
        the timeout expires. A timeout of ``None`` waits indefinitely.
        """
        deadline = None if timeout is None else time.monotonic() + timeout
        with self._condition:
            while self._generation <= last_generation:
                if deadline is None:
                    self._condition.wait()
                    continue
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None, self._generation
                self._condition.wait(remaining)
            return self._frame, self._generation


class RateGate:
    """Caps how often a loop may run, e.g. detection at 2-3 inferences/second."""

    def __init__(self, max_rate_hz):
        rate = float(max_rate_hz)
        if rate <= 0:
            raise ValueError("max_rate_hz must be greater than zero")
        self.interval = 1.0 / rate
        self._last_run = None

    def ready(self, now=None):
        """Return True when the next run is allowed."""
        if now is None:
            now = time.monotonic()
        if self._last_run is None:
            return True
        return (now - self._last_run) >= self.interval

    def mark(self, now=None):
        """Record that a run happened now."""
        self._last_run = time.monotonic() if now is None else now

    def remaining(self, now=None):
        """Seconds until the next run is allowed (0 when ready)."""
        if now is None:
            now = time.monotonic()
        if self._last_run is None:
            return 0.0
        return max(0.0, self.interval - (now - self._last_run))


def normalize_motion(delta_px, dt):
    """Convert displacement between two detections to 30 FPS px/frame units.

    Detection runs far slower than the camera, so raw pixel displacement grows
    with the sampling interval. Dividing by the elapsed time (and the reference
    frame rate) keeps the existing motion thresholds meaningful.
    """
    safe_dt = max(float(dt), _MIN_DT)
    return max(0.0, float(delta_px)) / safe_dt / REFERENCE_FPS
