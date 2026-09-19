#!/usr/bin/env python3
"""Quick test to verify a trained poultry detector loads and runs inference.

Usage:
  ./venv/bin/python test_model_integration.py --model /path/to/model.pt --image /path/to/sample.jpg

If no `--model` is given the script looks for:
  training/models/chicken_detector.pt
or the environment variable `POULTRYSENSE_MODEL_PATH`.

It prints model info and any detections.
"""

from __future__ import annotations
import argparse
import os
import sys
from pathlib import Path

try:
    from ultralytics import YOLO
except Exception as e:
    print("[ERROR] ultralytics not installed. Activate venv and pip install ultralytics")
    raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", help="Path to model weights (.pt)")
    parser.add_argument("--image", help="Path to sample image to run inference on", default=None)
    args = parser.parse_args()

    model_path = args.model or os.environ.get("POULTRYSENSE_MODEL_PATH") or Path(__file__).resolve().parents[0] / "training" / "models" / "chicken_detector.pt"
    model_path = str(model_path)

    print(f"[INFO] Loading model: {model_path}")
    if not Path(model_path).exists():
        print(f"[ERROR] Model not found at: {model_path}")
        sys.exit(2)

    model = YOLO(model_path)
    print(f"[OK] Model loaded: {model}")

    if args.image:
        img = args.image
        print(f"[INFO] Running inference on: {img}")
        results = model(img, conf=0.25, verbose=False)
        if not results:
            print("[WARN] No results returned")
            return
        r = results[0]
        boxes = getattr(r, 'boxes', None)
        if boxes is None:
            print("[WARN] No boxes attribute in results")
            return
        dets = []
        if len(boxes) > 0:
            confs = boxes.conf.cpu().tolist()
            xyxys = boxes.xyxy.cpu().tolist()
            for box, conf in zip(xyxys, confs):
                dets.append({'bbox': box, 'conf': float(conf)})
        print(f"[RESULT] Detected {len(dets)} objects")
        for i, d in enumerate(dets, 1):
            print(f"  {i}: bbox={d['bbox']} conf={d['conf']:.2f}")
    else:
        print("[INFO] No image provided. Use --image to run a quick inference test.")


if __name__ == '__main__':
    main()
