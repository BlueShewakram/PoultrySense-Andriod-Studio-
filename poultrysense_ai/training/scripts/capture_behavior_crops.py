#!/usr/bin/env python3
"""
Capture chicken crops from the webcam and auto-label them using the
behavior/posture health engine (motion + bbox geometry).

Saves into:
  datasets/behavior_captures/{normal,lethargic,abnormal_posture}/

Use these crops to train a YOLO classifier later.
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import cv2

# Allow importing sibling package modules
AI_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(AI_ROOT))

from behavior_health import BehaviorHealthEngine  # noqa: E402
import poultrysense_ai as appmod  # noqa: E402


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument("--seconds", type=int, default=60, help="Capture duration")
    parser.add_argument("--every", type=float, default=1.0, help="Seconds between saved crops")
    parser.add_argument(
        "--out",
        default=str(root / "datasets" / "behavior_captures"),
    )
    parser.add_argument(
        "--classes",
        default="14",
        help="YOLO class ids to detect (14=bird). Comma-separated. Add 0 for person fallback.",
    )
    args = parser.parse_args()

    out = Path(args.out)
    for name in ("normal", "lethargic", "abnormal_posture"):
        (out / name).mkdir(parents=True, exist_ok=True)

    class_ids = [int(x.strip()) for x in args.classes.split(",") if x.strip()]

    print("[INFO] Loading YOLO…")
    from ultralytics import YOLO

    model_path = AI_ROOT / "yolo11n.pt"
    model = YOLO(str(model_path if model_path.exists() else "yolo11n.pt"))

    cap = appmod.open_camera()
    if cap is None:
        raise SystemExit("Camera unavailable — grant macOS Camera permission and retry.")

    engine = BehaviorHealthEngine()
    tracker = appmod.PersonReIDTracker(reid_thresh=0.62)

    print(f"[INFO] Capturing for {args.seconds}s → {out}")
    print("[INFO] Move around / sit still to generate normal vs lethargic / posture samples.")

    start = time.time()
    last_save = 0.0
    saved = {"normal": 0, "lethargic": 0, "abnormal_posture": 0}

    while time.time() - start < args.seconds:
        ok, frame = cap.read()
        if not ok or frame is None:
            continue
        frame = cv2.flip(frame, 1)
        results = model(frame, classes=class_ids, conf=0.35, iou=0.45, verbose=False)
        raw = []
        if results and results[0].boxes is not None:
            boxes = results[0].boxes
            for box, conf in zip(boxes.xyxy.cpu().tolist(), boxes.conf.cpu().tolist()):
                raw.append((box, conf))

        tracks = tracker.update(frame, raw)
        now = time.time()
        if now - last_save >= args.every:
            for tid, trk in tracks.items():
                bbox = trk["bbox"]
                health = engine.observe(tid, bbox, frame.shape, now=now)
                behavior = health["behavior"]
                x1, y1, x2, y2 = map(int, bbox)
                pad = 8
                x1, y1 = max(0, x1 - pad), max(0, y1 - pad)
                x2, y2 = min(frame.shape[1], x2 + pad), min(frame.shape[0], y2 + pad)
                crop = frame[y1:y2, x1:x2]
                if crop.size == 0:
                    continue
                fname = out / behavior / f"id{tid}_{int(now * 1000)}.jpg"
                cv2.imwrite(str(fname), crop)
                saved[behavior] = saved.get(behavior, 0) + 1
            last_save = now

        # Preview HUD
        preview = frame.copy()
        cv2.putText(
            preview,
            f"Capturing behavior crops  saved={sum(saved.values())}",
            (12, 28),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.7,
            (0, 230, 118),
            2,
        )
        cv2.imshow("PoultrySense capture (q to quit)", preview)
        if cv2.waitKey(1) & 0xFF == ord("q"):
            break

    cap.release()
    cv2.destroyAllWindows()
    print("[DONE] Saved:", saved)
    print("Next:")
    print("  python scripts/prepare_behavior_dataset.py --from-captures")
    print("  python scripts/train_behavior_classifier.py")


if __name__ == "__main__":
    main()
