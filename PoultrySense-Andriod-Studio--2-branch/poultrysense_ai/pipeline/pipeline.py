"""PoultrySense Phase 9 - Integrated inference pipeline.

Camera/Video -> YOLOv8 detection -> ByteTrack tracking -> per-chicken ID
-> behavior (image + motion) + health (weak signal) -> isolation logic
-> temporal smoothing -> per-chicken risk status -> JSON + annotated video.

Honest note: health classifier was trained on a 7-image test set, so it is
reported as a weak health indicator, NOT a medical diagnosis.
"""

import os
import json
import time
import cv2
import numpy as np
from collections import deque, defaultdict
from ultralytics import YOLO

from tracker import TRACKER_CFG, TRACKER_YAML

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
MODEL_DIR = os.path.join(os.path.dirname(__file__), "..", "models")
# Default detection model: D3+D4 fine-tune (wide-angle crowded pen). Tracks only
# need boxes, so a detection model replaces the older close-up seg model.
# Fall back to the seg model with --model models/PoultrySense_YOLOv8_ChickenSeg.pt
SEG_MODEL = os.path.join(MODEL_DIR, "PoultrySense_YOLOv8_ChickenDet.pt")
SEG_MODEL_FALLBACK = os.path.join(MODEL_DIR, "PoultrySense_YOLOv8_ChickenSeg.pt")
HEALTH_MODEL = os.path.join(MODEL_DIR, "PoultrySense_HealthClassifier.pt")
BEHAVIOR_MODEL = os.path.join(MODEL_DIR, "PoultrySense_BehaviorModel.pt")

BEHAVIOR_NAMES = {0: "peck", 1: "stand", 2: "walk"}
HEALTH_NAMES = {0: "Healthy", 1: "Unhealthy"}

# Isolation: flag a chicken if its nearest neighbor is farther than this
# fraction of the frame width (spatial logic, calibrated for a 10-chicken pen).
ISOLATION_FRACTION = 0.25

# Motion thresholds (px/frame from D3 motion analysis):
#   stand ~2.47, peck ~2.94, walk ~8.17
MOTION_WALK_THRESHOLD = 5.0
MOTION_PECK_THRESHOLD = 3.5

# Temporal smoothing window (frames) for per-ID signals.
SMOOTH_WINDOW = 15

# Static-object (feeder) filter: a track that is present for a very long time
# AND never wanders from its location (rolling-window span below a threshold)
# is treated as a stationary object (e.g. chicken feeder) and excluded from
# chicken tracking/annotation/counts. Calibrated on job_000011:
#   feeders:    ID 4 -> 312 frames, 2.1px  span   -> flagged
#               ID 17 -> 365 frames, 23.5px span   -> flagged
#   resting chickens (kept):
#               ID 21 -> 132 frames, 30.2px span (fails duration test)
#               ID 9  ->  74 frames, 16.3px span (fails duration test)
#               ID 19 -> 152 frames, 53.9px span (fails span test)
# The rolling span tolerates box jitter so an occasionally-wiggling feeder is
# still caught; resting chickens are kept because they never stay still for
# the whole session. Calibration notes: resting chicken ID 9 disappears after
# 74 frames (< 90), and resting chicken ID 21 has a 30px span (> 25), so a
# 90-frame / 25px rule flags only the feeders. Trade-off (honest): a feeder
# stays boxed for the first FEEDER_MIN_FRAMES frames until identified.
#
# NOTE: this filter was calibrated for the old segmentation model and is
# DISABLED by default. The current detection model is trained only on
# chickens, so a feeder is never detected as a chicken, and the filter only
# removes resting chickens (which is what we want to monitor). Re-enable with
# --feeder-filter only on recordings where the detector still boxes feeders.
FEEDER_MIN_FRAMES = 90    # must be seen at least this many frames
FEEDER_MAX_SPAN = 25.0    # max centroid span (px) inside a rolling window
FEEDER_WINDOW = 90        # rolling window (frames) for the span test

# Health class display label (honest display only; raw class kept in JSON).
HEALTH_DISPLAY = {"Healthy": "Normal", "Unhealthy": "Unhealthy"}

# Box persistence: when the detector misses a frame for a known track, keep
# drawing its last-known box for up to this many frames so the box "sticks" to
# a visible chicken during detection flicker. Persisted boxes are NOT written
# to the JSON detections (honest: not detections). The window matches
# ByteTrack's track_buffer (30) so a chicken that disappears and reappears as
# a new track reuses its old display id instead of getting a duplicate.
PERSIST_WINDOW = 30

# Persisted boxes are drawn at their last-known position (no velocity
# extrapolation) only within this short grace window; beyond it the box is
# dropped entirely so it never drifts onto empty floor.
PERSIST_DRAW_LIMIT = 0

# Drawn-box smoothing factor (EMA): 1.0 = raw detector box, 0.0 = frozen.
# A moderate value removes per-frame jitter so the box stays steady on the
# chicken. Raw boxes are still written to the JSON detections.
BOX_SMOOTH_ALPHA = 0.45


class PoultrySense:
    """End-to-end per-chicken monitor."""

    def __init__(self, model=None, conf=0.35, imgsz=960, max_det=16,
                 device="cpu", verbose=False, feeder_filter=False,
                 persist_draw_limit=0):
        self.conf = conf
        self.imgsz = imgsz
        self.max_det = max_det
        self.verbose = verbose
        self.feeder_filter = feeder_filter
        self.persist_draw_limit = persist_draw_limit
        self.seg = YOLO(model or SEG_MODEL)
        self.health = YOLO(HEALTH_MODEL)
        self.behavior = YOLO(BEHAVIOR_MODEL)
        self.history = defaultdict(lambda: deque(maxlen=SMOOTH_WINDOW))
        self.static_state = {}
        # tid -> {"box": xyxy, "cent": (x,y), "last_frame": int,
        #         "cent_hist": deque[(frame, x, y)]}
        self.track_state = {}
        # Display-ID manager: maps ByteTrack tid -> stable display id (1..N).
        # A chicken keeps its display id while visible; when it leaves the
        # screen (missed longer than PERSIST_WINDOW) the id is freed and can be
        # reused by the next new chicken, so ids stay small and contiguous.
        self.display_id = {}      # tid -> display id
        self.track_missed = {}    # tid -> consecutive missed frames
        self.smooth_box = {}      # tid -> EMA-smoothed drawn box (xyxy)

    def _update_static(self, tid, cx, cy):
        """Track presence + rolling-window centroid span; return True if the
        track should be treated as a static object (e.g. feeder). A track that
        is present for FEEDER_MIN_FRAMES frames AND never wanders beyond
        FEEDER_MAX_SPAN in a FEEDER_WINDOW-frame window is a static object."""
        st = self.static_state.setdefault(tid, {
            "count": 0, "window": deque(maxlen=FEEDER_WINDOW),
            "flagged": False,
        })
        st["count"] += 1
        st["window"].append((cx, cy))
        if st["flagged"]:
            return True
        if st["count"] < FEEDER_MIN_FRAMES or len(st["window"]) < FEEDER_WINDOW:
            return False
        xs = [p[0] for p in st["window"]]
        ys = [p[1] for p in st["window"]]
        span = max(max(xs) - min(xs), max(ys) - min(ys))
        if span <= FEEDER_MAX_SPAN:
            st["flagged"] = True
        return st["flagged"]

    def _display_ids(self, visible_tids, frame_idx):
        """Return {tid: display_id} for the visible tracks this frame.

        Each chicken keeps a permanent display id while it stays visible. A tid
        that was visible before but is missing now is kept for PERSIST_WINDOW
        frames (so it keeps its id across a short detector miss), then freed.
        Freed ids are reused by new chickens, keeping ids small/contiguous.
        """
        for tid in list(self.display_id):
            if tid not in visible_tids:
                missed = self.track_missed.get(tid, 0) + 1
                self.track_missed[tid] = missed
                if missed > PERSIST_WINDOW:
                    del self.display_id[tid]
                    del self.track_missed[tid]
        result = {}
        for tid in visible_tids:
            if tid not in self.display_id:
                used = set(self.display_id.values())
                nid = 1
                while nid in used:
                    nid += 1
                self.display_id[tid] = nid
            self.track_missed[tid] = 0
            result[tid] = self.display_id[tid]
        return result

    # -- per-frame helpers -------------------------------------------------
    def _crop(self, frame, box, pad=0.15, min_side=160):
        H, W = frame.shape[:2]
        x1, y1, x2, y2 = box
        bw, bh = x2 - x1, y2 - y1
        cx, cy = (x1 + x2) / 2.0, (y1 + y2) / 2.0
        pad_w, pad_h = int(bw * pad), int(bh * pad)
        x1 = int(max(0, x1 - pad_w)); x2 = int(min(W, x2 + pad_w))
        y1 = int(max(0, y1 - pad_h)); y2 = int(min(H, y2 + pad_h))
        half_w = max((x2 - x1) / 2.0, min_side / 2.0)
        half_h = max((y2 - y1) / 2.0, min_side / 2.0)
        x1 = int(max(0, cx - half_w)); x2 = int(min(W, cx + half_w))
        y1 = int(max(0, cy - half_h)); y2 = int(min(H, cy + half_h))
        if x2 - x1 < 40 or y2 - y1 < 40:
            return None
        return frame[y1:y2, x1:x2]

    def _classify(self, model, crop, names):
        r = model.predict(crop, imgsz=224, verbose=False)[0]
        top1 = int(r.probs.top1)
        return names[top1], float(r.probs.top1conf)

    def _motion_signal(self, track_id, centroid):
        hist = self.history[track_id]
        motion = 0.0
        if hist and hist[-1][0] == "centroid":
            motion = float(np.hypot(centroid[0] - hist[-1][1][0],
                                    centroid[1] - hist[-1][1][1]))
        hist.append(("centroid", centroid))
        return motion

    def _behavior_fused(self, track_id, crop, frame):
        """Combine image classifier + motion into a fused behavior label."""
        cls, cls_conf = self._classify(self.behavior, crop, BEHAVIOR_NAMES)

        # centroid from last track position
        hist = self.history[track_id]
        cent = None
        for entry in reversed(list(hist)):
            if entry[0] == "centroid":
                cent = entry[1]
                break
        motion = self._motion_signal(track_id, cent or (0, 0))

        # Motion overrides image when walking is clearly indicated.
        if motion > MOTION_WALK_THRESHOLD:
            fused, reason = "walk", "motion"
        elif motion > MOTION_PECK_THRESHOLD and cls == "peck":
            fused, reason = "peck", "image+motion"
        else:
            fused, reason = cls, "image"
        return fused, float(cls_conf), motion, reason

    # -- main loop ----------------------------------------------------------
    def run(self, video_path, out_path=None, out_json=None, show=False):
        if isinstance(video_path, str) and video_path.isdigit():
            source = int(video_path)
            # Default to showing live window if source is a live camera and not specified otherwise
            if not show:
                show = True
        else:
            source = video_path

        cap = cv2.VideoCapture(source)
        if not cap.isOpened():
            raise FileNotFoundError(f"Cannot open video/camera source: {video_path}")

        fps = cap.get(cv2.CAP_PROP_FPS) or 25.0
        w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

        writer = None
        if out_path:
            writer = cv2.VideoWriter(out_path, cv2.VideoWriter_fourcc(*"mp4v"),
                                     fps, (w, h))

        session = {"chickens": {}, "frames": [], "fps_in": fps}
        frame_idx = 0
        t_infer = 0.0

        print(f"Starting tracking on source: {video_path}...")
        if show:
            print("Live window enabled. Press 'q' or 'ESC' on the live window to quit.")

        while True:
            ok, frame = cap.read()
            if not ok:
                break

            t0 = time.time()
            results = self.seg.track(
                frame, persist=True, conf=self.conf, iou=0.5, imgsz=self.imgsz,
                tracker=TRACKER_YAML, max_det=self.max_det, verbose=False,
            )[0]
            t_infer += time.time() - t0

            boxes = results.boxes
            ids = boxes.id if boxes is not None and boxes.id is not None else None
            frame_dets = []
            disp = {}

            if boxes is not None and len(boxes) > 0:
                xyxy = boxes.xyxy.cpu().numpy()
                confs = boxes.conf.cpu().numpy()
                ids_n = ids.cpu().numpy() if ids is not None else np.arange(len(xyxy))
                cents = ((xyxy[:, 0] + xyxy[:, 2]) / 2.0,
                         (xyxy[:, 1] + xyxy[:, 3]) / 2.0)

                # --- static-object (feeder) filter (opt-in; see config note).
                # When enabled, update state first, then drop any tracks that
                # are stationary objects (e.g. feeder).
                if self.feeder_filter:
                    feeder_flags = [
                        self._update_static(
                            int(ids_n[i]), float(cents[0][i]), float(cents[1][i]))
                        for i in range(len(xyxy))
                    ]
                    keep = [i for i in range(len(xyxy)) if not feeder_flags[i]]
                    if len(keep) != len(xyxy):
                        xyxy = xyxy[keep]
                        confs = confs[keep]
                        ids_n = ids_n[keep]
                        cents = ((xyxy[:, 0] + xyxy[:, 2]) / 2.0,
                                 (xyxy[:, 1] + xyxy[:, 3]) / 2.0)

                # --- stable display IDs for the chickens visible this frame ---
                disp = self._display_ids({int(t) for t in ids_n}, frame_idx)

                if len(xyxy) > 0:
                    # --- isolation logic: nearest-neighbor distance per chicken ---
                    # Only meaningful when >1 chicken is visible; a lone chicken in
                    # frame is "unknown", NOT automatically isolated.
                    isolation = {}
                    for i in range(len(xyxy)):
                        dists = []
                        for j in range(len(xyxy)):
                            if i == j:
                                continue
                            dists.append(float(np.hypot(cents[0][i] - cents[0][j],
                                                        cents[1][i] - cents[1][j])))
                        isolation[i] = min(dists) if dists else None

                    for i, box in enumerate(xyxy):
                        tid = int(ids_n[i])
                        crop = self._crop(frame, box)
                        if crop is None:
                            continue

                        behavior, b_conf, motion, reason = self._behavior_fused(
                            tid, crop, frame)
                        health, h_conf = self._classify(
                            self.health, crop, HEALTH_NAMES)

                        if isolation[i] is None:
                            iso_norm = None
                            isolated = None  # unknown (only one chicken visible)
                        else:
                            iso_norm = isolation[i] / float(w)
                            isolated = iso_norm > ISOLATION_FRACTION

                        entry = {
                            "frame": frame_idx,
                            "track_id": tid,
                            "id": disp[tid],
                            "conf": float(confs[i]),
                            "box": [float(v) for v in box],
                            "behavior": behavior,
                            "behavior_conf": b_conf,
                            "behavior_source": reason,
                            "motion_px": motion,
                            "health": health,
                            "health_conf": h_conf,
                            "nearest_neighbor_px": isolation[i],
                            "isolation_norm": None if iso_norm is None else round(iso_norm, 3),
                            "isolated": isolated,
                        }
                        frame_dets.append(entry)

                        # accumulate per-chicken history for temporal smoothing
                        # keyed by the stable display id
                        ch = session["chickens"].setdefault(disp[tid], {
                            "behaviors": [], "healths": [],
                            "isolation": [], "last_seen": frame_idx,
                        })
                        ch["behaviors"].append(behavior)
                        ch["healths"].append(health)
                        ch["isolation"].append(isolated)
                        ch["last_seen"] = frame_idx

                    # remember last-known box/centroid for persistence
                    for i, box in enumerate(xyxy):
                        tid = int(ids_n[i])
                        st = self.track_state.setdefault(tid, {
                            "box": None, "cent": None, "last_frame": -1,
                            "cent_hist": deque(maxlen=5),
                        })
                        cx, cy = float(cents[0][i]), float(cents[1][i])
                        st["box"] = [float(v) for v in box]
                        st["cent"] = (cx, cy)
                        st["last_frame"] = frame_idx
                        st["cent_hist"].append((frame_idx, cx, cy))

            session["frames"].append({"frame": frame_idx, "detections": frame_dets})

            if writer is not None or show:
                annotated = frame.copy()
                GREEN = (0, 255, 0)

                visible_tids = set(disp)

                # --- box persistence: draw last-known box for tracks that were
                # recently seen but missed this frame.
                persisted = []
                for tid, st in self.track_state.items():
                    if tid in visible_tids or st["box"] is None:
                        continue
                    gap = frame_idx - st["last_frame"]
                    if gap <= 0 or gap > self.persist_draw_limit:
                        continue
                    persisted.append({
                        "track_id": tid,
                        "box": st["box"],
                        "gap": gap,
                    })

                # --- EMA-smoothed boxes for stable drawing ---
                draw_boxes = {}
                for d in frame_dets:
                    tid = d["track_id"]
                    b = d["box"]
                    prev = self.smooth_box.get(tid)
                    if prev is None:
                        draw_boxes[tid] = b
                    else:
                        a = BOX_SMOOTH_ALPHA
                        draw_boxes[tid] = [a * prev[i] + (1 - a) * b[i]
                                           for i in range(4)]
                for tid, b in draw_boxes.items():
                    self.smooth_box[tid] = b

                for p in persisted:
                    tid = p["track_id"]
                    x1, y1, x2, y2 = [int(v) for v in p["box"]]
                    pid = self.display_id.get(tid, tid)
                    cv2.rectangle(annotated, (x1, y1), (x2, y2), GREEN, 2)
                    cv2.putText(annotated, f"ID {pid}",
                                (x1, max(15, y1 - 5)),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.7, GREEN, 2)

                for d in frame_dets:
                    x1, y1, x2, y2 = [int(v) for v in draw_boxes[d["track_id"]]]
                    cv2.rectangle(annotated, (x1, y1), (x2, y2), GREEN, 2)
                    cv2.putText(annotated, f"ID {d['id']}",
                                (x1, max(15, y1 - 5)),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.7, GREEN, 2)

                # --- No chicken detected overlay ---
                if len(frame_dets) == 0:
                    cv2.rectangle(annotated, (15, 15), (370, 60), (0, 0, 0), -1)
                    cv2.putText(annotated, "No Chicken detected", (25, 47),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 0, 255), 2, cv2.LINE_AA)

                if writer is not None:
                    writer.write(annotated)

                if show:
                    cv2.imshow("PoultrySense - Live Tracking", annotated)
                    key = cv2.waitKey(1) & 0xFF
                    if key == ord('q') or key == 27:
                        print("User requested quit from live display.")
                        break

            frame_idx += 1
            if not self.verbose and frame_idx % 50 == 0:
                print(f"  processed {frame_idx} frames")

        cap.release()
        if writer is not None:
            writer.release()
        if show:
            cv2.destroyAllWindows()

        avg_fps = frame_idx / t_infer if t_infer > 0 else 0.0
        self._finalize(session, frame_idx, avg_fps)

        if out_json:
            with open(out_json, "w") as f:
                json.dump(session, f, indent=1)
            print("JSON saved to", out_json)
        return session

    def _finalize(self, session, n_frames, avg_fps):
        """Per-chicken summary with temporal smoothing + risk status."""
        summary = []
        for tid, ch in session["chickens"].items():
            if not ch["behaviors"]:
                continue
            n = len(ch["behaviors"])
            behavior_counts = defaultdict(int)
            for b in ch["behaviors"]:
                behavior_counts[b] += 1
            top_behavior = max(behavior_counts, key=behavior_counts.get)

            health_counts = defaultdict(int)
            for hh in ch["healths"]:
                health_counts[hh] += 1
            top_health = max(health_counts, key=health_counts.get)

            iso_vals = [x for x in ch["isolation"] if x is not None]
            iso_frac = (sum(iso_vals) / len(iso_vals)) if iso_vals else None
            health_frac = health_counts.get("Unhealthy", 0) / max(1, n)

            # Simple risk logic (no invented medical thresholds).
            risk = "Normal"
            reasons = []
            if iso_frac is not None and iso_frac > 0.5:
                risk, reasons = "Warning", ["isolated"]
            elif health_frac > 0.5:
                risk = "Potential Health Risk"
                reasons = ["health indicator: Unhealthy"]
            elif top_behavior == "walk" and health_frac > 0.3:
                risk = "Observation"
                reasons = ["high walking + health signal"]

            summary.append({
                "chicken_id": tid,
                "frames_seen": n,
                "dominant_behavior": top_behavior,
                "behavior_distribution": dict(behavior_counts),
                "dominant_health": top_health,
                "health_healthy_frac": health_counts.get("Healthy", 0) / max(1, n),
                "health_unhealthy_frac": health_frac,
                "isolation_fraction": None if iso_frac is None else round(iso_frac, 3),
                "risk_status": risk,
                "risk_reasons": reasons,
            })

        summary.sort(key=lambda x: x["chicken_id"])
        session["summary"] = summary
        session["metrics"] = {
            "frames_processed": n_frames,
            "avg_inference_fps": round(avg_fps, 1),
            "device": "cpu",
        }
        print("\nPer-chicken summary:")
        for s in summary:
            iso_txt = "n/a" if s["isolation_fraction"] is None else f"{s['isolation_fraction']:.2f}"
            disp_health = HEALTH_DISPLAY.get(s["dominant_health"], s["dominant_health"])
            print(f"  Chicken {s['chicken_id']:>2d}: {s['dominant_behavior']:>5s}"
                  f" | {disp_health:>9s}"
                  f" | iso={iso_txt}"
                  f" | {s['risk_status']}")
        print(f"\nAverage inference: {avg_fps:.1f} FPS (CPU)")


if __name__ == "__main__":
    import argparse

    p = argparse.ArgumentParser(description="PoultrySense integrated pipeline")
    p.add_argument("video", help="input video path or camera index (e.g. 0 for primary USB camera)")
    p.add_argument("--out", default=None, help="output annotated video path")
    p.add_argument("--json", default=None, help="output JSON results path")
    p.add_argument("--show", action="store_true", help="display live tracking window (press 'q' or 'ESC' to quit)")
    p.add_argument("--conf", type=float, default=0.35, help="detection confidence threshold (default: 0.35)")
    p.add_argument("--imgsz", type=int, default=960)
    p.add_argument("--model", default=None,
                   help="path to detection/segmentation model (.pt)")
    p.add_argument("--feeder-filter", action="store_true",
                   help="enable the static-object (feeder) filter")
    p.add_argument("--persist-draw", type=int, default=0,
                   help="number of missed frames to keep drawing last-known box (default: 0)")
    args = p.parse_args()

    ps = PoultrySense(model=args.model, conf=args.conf, imgsz=args.imgsz,
                      feeder_filter=args.feeder_filter,
                      persist_draw_limit=args.persist_draw)
    session = ps.run(args.video, out_path=args.out, out_json=args.json, show=args.show)
    if args.out:
        print("Annotated video saved to", args.out)