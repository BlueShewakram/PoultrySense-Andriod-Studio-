#!/usr/bin/env python3
"""Train YOLO11 classifier on behavior/posture crops (normal / lethargic / abnormal_posture)."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from ultralytics import YOLO


def pick_device() -> str:
    import torch

    if torch.backends.mps.is_available():
        return "mps"
    if torch.cuda.is_available():
        return "0"
    return "cpu"


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default=str(root / "datasets" / "behavior_cls"))
    parser.add_argument("--model", default="yolo11n-cls.pt")
    parser.add_argument("--epochs", type=int, default=40)
    parser.add_argument("--imgsz", type=int, default=224)
    parser.add_argument("--batch", type=int, default=32)
    parser.add_argument("--name", default="behavior_cls_v1")
    parser.add_argument("--device", default=None)
    args = parser.parse_args()

    data = Path(args.data)
    train_dir = data / "train"
    if not train_dir.exists():
        raise SystemExit(
            f"Missing {train_dir}. Run capture_behavior_crops.py then "
            "prepare_behavior_dataset.py --from-captures"
        )

    classes = sorted([p.name for p in train_dir.iterdir() if p.is_dir()])
    if len(classes) < 2:
        raise SystemExit(f"Need >=2 class folders with images under {train_dir}, found {classes}")

    # Ensure each class has at least 1 image
    for cls in classes:
        n = len(list((train_dir / cls).glob("*")))
        if n == 0:
            raise SystemExit(f"Class folder empty: {train_dir / cls}")

    device = args.device or pick_device()
    runs_dir = root / "runs" / "classify"
    export_dir = root / "models"
    export_dir.mkdir(parents=True, exist_ok=True)

    print("=" * 60)
    print(" PoultrySense — Behavior/Posture Classifier Training")
    print("=" * 60)
    print(f" data:    {data}")
    print(f" classes: {classes}")
    print(f" device:  {device}")
    print("=" * 60)

    model = YOLO(args.model)
    model.train(
        data=str(data),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        device=device,
        project=str(runs_dir),
        name=args.name,
        exist_ok=True,
        pretrained=True,
        patience=12,
        verbose=True,
    )

    best = runs_dir / args.name / "weights" / "best.pt"
    dest = export_dir / "chicken_behavior_cls.pt"
    if best.exists():
        dest.write_bytes(best.read_bytes())
        print(f"[DONE] Copied best weights → {dest}")

    meta = {
        "task": "behavior_posture_classification",
        "classes": classes,
        "modality": "chicken_appearance_crops",
        "not_used": "fecal_droppings",
        "data": str(data),
        "weights": str(dest),
        "run_dir": str(runs_dir / args.name),
    }
    (export_dir / "chicken_behavior_cls.json").write_text(json.dumps(meta, indent=2))
    print("[DONE] Metadata written. Use predict_behavior.py to test.")


if __name__ == "__main__":
    main()
