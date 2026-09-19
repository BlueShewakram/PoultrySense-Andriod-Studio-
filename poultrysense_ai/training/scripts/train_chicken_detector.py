#!/usr/bin/env python3
"""Train a custom poultry object-detection model with Ultralytics YOLO."""

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


def ensure_data_yaml(data_dir: Path) -> Path:
    images_train = data_dir / "images" / "train"
    labels_train = data_dir / "labels" / "train"
    images_val = data_dir / "images" / "val"
    labels_val = data_dir / "labels" / "val"

    if not (images_train.exists() and labels_train.exists() and images_val.exists() and labels_val.exists()):
        raise SystemExit(
            "Missing YOLO detection dataset. Expected folders:\n"
            f"  {images_train}\n"
            f"  {labels_train}\n"
            f"  {images_val}\n"
            f"  {labels_val}\n\n"
            "Create a standard YOLO dataset with one class named 'chicken' and run again."
        )

    yaml_path = data_dir / "data.yaml"
    if not yaml_path.exists():
        yaml_path.write_text(
            "\n".join(
                [
                    "train: images/train",
                    "val: images/val",
                    "nc: 1",
                    "names: ['chicken']",
                ]
            )
            + "\n",
            encoding="utf-8",
        )
    return yaml_path


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", default=str(root / "datasets" / "chicken_det"))
    parser.add_argument("--weights", default="yolo11n.pt")
    parser.add_argument("--epochs", type=int, default=50)
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--batch", type=int, default=16)
    parser.add_argument("--name", default="poultry_detector_v1")
    parser.add_argument("--device", default=None)
    args = parser.parse_args()

    data_dir = Path(args.data)
    yaml_path = ensure_data_yaml(data_dir)
    device = args.device or pick_device()

    runs_dir = root / "runs" / "detect"
    export_dir = root / "models"
    export_dir.mkdir(parents=True, exist_ok=True)

    print("=" * 72)
    print(" PoultrySense — Custom Chicken Detector Training")
    print("=" * 72)
    print(f" data:    {yaml_path}")
    print(f" weights: {args.weights}")
    print(f" device:  {device}")
    print("=" * 72)

    model = YOLO(args.weights)
    model.train(
        data=str(yaml_path),
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
    custom_model = export_dir / "chicken_detector.pt"
    if best.exists():
        custom_model.write_bytes(best.read_bytes())
        print(f"[DONE] Copied best weights → {custom_model}")

    meta = {
        "task": "poultry_detection",
        "classes": ["chicken"],
        "weights": str(custom_model),
        "data": str(yaml_path),
        "run_dir": str(runs_dir / args.name),
        "notes": "Custom poultry detector for the live camera pipeline; preferred over generic bird detection.",
    }
    (export_dir / "chicken_detector.json").write_text(json.dumps(meta, indent=2))
    print("[DONE] Chicken detector training completed. Use env POULTRYSENSE_MODEL_PATH to point the live server at the model.")


if __name__ == "__main__":
    main()
