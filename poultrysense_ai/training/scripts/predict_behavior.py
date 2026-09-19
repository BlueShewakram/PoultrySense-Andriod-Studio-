#!/usr/bin/env python3
"""Predict behavior/posture class for chicken crop images."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from ultralytics import YOLO


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument("images", nargs="+")
    parser.add_argument("--weights", default=str(root / "models" / "chicken_behavior_cls.pt"))
    parser.add_argument("--imgsz", type=int, default=224)
    args = parser.parse_args()

    weights = Path(args.weights)
    if not weights.exists():
        raise SystemExit(
            f"Missing {weights}. Capture crops → prepare → train first, "
            "or rely on the live BehaviorHealthEngine (no weights needed)."
        )

    model = YOLO(str(weights))
    results = model.predict(source=args.images, imgsz=args.imgsz, verbose=False)
    for path, result in zip(args.images, results):
        if result.probs is None:
            print(json.dumps({"image": path, "error": "no_probs"}))
            continue
        top = int(result.probs.top1)
        name = str(result.names.get(top, top))
        conf = float(result.probs.top1conf)
        status = "HEALTHY" if name == "normal" else "ILL"
        print(
            json.dumps(
                {
                    "image": path,
                    "behavior": name,
                    "status": status,
                    "confidence": round(conf, 4),
                }
            )
        )


if __name__ == "__main__":
    main()
